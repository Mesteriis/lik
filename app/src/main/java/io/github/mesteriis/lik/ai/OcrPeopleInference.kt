@file:Suppress("UseKtx")
package io.github.mesteriis.lik.ai

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.core.net.toUri
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.catalog.MediaRecord
import io.github.mesteriis.lik.catalog.MediaSource
import io.github.mesteriis.lik.imports.PhotoLibrary
import java.io.File
import kotlin.math.*

data class OcrRegion(val box: FaceBox, val text: String, val confidence: Float)
data class OcrInference(val regions: List<OcrRegion>) {
    val displayText get() = OcrText.normalizeDisplay(regions.joinToString("\n") { it.text })
    val confidence get() = regions.map(OcrRegion::confidence).average().takeIf(Double::isFinite)?.toFloat() ?: 0f
}
data class FaceInference(val box: FaceBox, val landmarks: FloatArray, val embedding: FloatArray, val confidence: Float)

@android.annotation.SuppressLint("UseKtx")
class OcrPeopleInferenceEngine(
    private val context: Context,
    private val catalog: ModelCatalog = ModelCatalog.get(context),
    private val artifacts: ArtifactStore = ArtifactStore(File(context.filesDir, "ai")),
    private val runtime: IsolatedRuntimeClient = IsolatedRuntimeClient(context),
) {
    fun ocr(profile: ProfileId, mediaId: String, expectedRevision: Long): OcrInference {
        val row = current(mediaId, expectedRevision)
        val bitmap = decode(row, 1600)
        return try { ocrBitmap(profile, bitmap) } finally { bitmap.recycle() }
    }

    fun people(mediaId: String, expectedRevision: Long): List<FaceInference> {
        val row = current(mediaId, expectedRevision)
        val bitmap = decode(row, 1600)
        return try { peopleBitmap(bitmap) } finally { bitmap.recycle() }
    }

    fun ocrBitmap(profile: ProfileId, bitmap: Bitmap): OcrInference {
        val detector = if (profile == ProfileId.EXTENDED) "ocr-server-det-v1/model.onnx" else "ocr-mobile-det-v1/model.onnx"
        val resized = OcrTensor.detector(bitmap)
        val probability = runtime.runFloat(file(detector), "x", resized.shape, resized.values, "fetch_name_0").getOrThrow()
        val boxes = DbRegions.rectangles(probability, resized.width, resized.height, bitmap.width, bitmap.height)
        val dictionary = file("ocr-cyrillic-rec-v1/characters.txt").readLines(Charsets.UTF_8) + " "
        val regions = boxes.mapNotNull { box ->
            val crop = crop(bitmap, box)
            try {
                val tensor = OcrTensor.recognizer(crop)
                val flat = runtime.runFloat(file("ocr-cyrillic-rec-v1/model.onnx"), "x", tensor.shape, tensor.values, "fetch_name_0").getOrThrow()
                if (flat.size % 852 != 0) error("OCR_OUTPUT_SHAPE")
                val decoded = CtcDecoder.decode(Array(flat.size / 852) { at -> flat.copyOfRange(at * 852, (at + 1) * 852) }, dictionary)
                decoded.takeIf { it.text.isNotBlank() }?.let { OcrRegion(box, it.text, it.confidence) }
            } finally { crop.recycle() }
        }.sortedWith(compareBy<OcrRegion> { it.box.top }.thenBy { it.box.left })
        return OcrInference(regions)
    }

    fun peopleBitmap(bitmap: Bitmap): List<FaceInference> {
        val tensor = FaceTensor.detector(bitmap)
        val names = listOf("cls_8","cls_16","cls_32","obj_8","obj_16","obj_32","bbox_8","bbox_16","bbox_32","kps_8","kps_16","kps_32")
        val flat = runtime.runFloatMulti(file("yunet-v1/model.onnx"), "input", intArrayOf(1,3,640,640), tensor.values, names).getOrThrow()
        val proposals = YuNetPostprocess.decode(flat, tensor.scaleX, tensor.scaleY, bitmap.width, bitmap.height)
        return proposals.map { proposal ->
            val aligned = FaceAlignment.align(bitmap, proposal.landmarks)
            try {
                val values = FaceTensor.recognizer(aligned)
                val raw = runtime.runFloat(file("sface-v1/model.onnx"), "data", intArrayOf(1,3,112,112), values, "fc1").getOrThrow()
                val norm = sqrt(raw.sumOf { it.toDouble() * it }).toFloat().also { require(it > 0) }
                FaceInference(proposal.box, proposal.landmarks, FloatArray(raw.size) { raw[it] / norm }, proposal.confidence)
            } finally { aligned.recycle() }
        }
    }

    private fun current(mediaId: String, revision: Long): MediaRecord = requireNotNull(MediaDatabase.get(context).media().get(mediaId)).also {
        require(it.availability.name == "AVAILABLE" && it.contentRevision == revision) { "PHOTO_CHANGED" }
    }
    private fun decode(row: MediaRecord, bound: Int): Bitmap {
        val source = if (row.source == MediaSource.DEVICE) ImageDecoder.createSource(context.contentResolver, requireNotNull(row.contentUri).toUri())
        else ImageDecoder.createSource(PhotoLibrary.store(context).fileFor(requireNotNull(row.privateFileId)))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height); decoder.setTargetSampleSize((longest / bound).coerceAtLeast(1))
        }
    }
    private fun file(path: String): File {
        val spec = catalog.trusted.allArtifacts.values.singleOrNull { it.path == path } ?: error("Unknown artifact $path")
        require(artifacts.installed(spec.sha256, spec.size)) { "PROFILE_NOT_INSTALLED" }
        return artifacts.file(spec.sha256)
    }
    private fun crop(bitmap: Bitmap, box: FaceBox): Bitmap {
        val left = (box.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (box.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = ceil(box.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ceil(box.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right-left, bottom-top)
    }
}

private data class TensorImage(val values: FloatArray, val shape: IntArray, val width: Int, val height: Int, val scaleX: Float, val scaleY: Float)
private object OcrTensor {
    fun detector(source: Bitmap): TensorImage {
        val ratio = 960f / max(source.width, source.height)
        val width = (ceil(max(32f, source.width * ratio) / 128f) * 128).toInt()
        val height = (ceil(max(32f, source.height * ratio) / 128f) * 128).toInt()
        val bitmap = Bitmap.createScaledBitmap(source, width, height, true)
        return try { TensorImage(channels(bitmap, floatArrayOf(.485f,.456f,.406f), floatArrayOf(.229f,.224f,.225f), bgr=true), intArrayOf(1,3,height,width), width,height,width/source.width.toFloat(),height/source.height.toFloat()) }
        finally { if (bitmap !== source) bitmap.recycle() }
    }
    fun recognizer(source: Bitmap): TensorImage {
        val rotated = if (source.height.toFloat()/source.width >= 1.5f) rotate(source) else source
        val width = min(320, ceil(48f * rotated.width / rotated.height).toInt()).coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(rotated, width, 48, true)
        val values = FloatArray(3*48*320)
        try {
            for (y in 0 until 48) for (x in 0 until width) {
                val c=resized.getPixel(x,y); val at=y*320+x; val plane=48*320
                values[at]=((c and 255)/255f-.5f)/.5f
                values[plane+at]=((c shr 8 and 255)/255f-.5f)/.5f
                values[2*plane+at]=((c shr 16 and 255)/255f-.5f)/.5f
            }
        } finally { if (resized !== rotated) resized.recycle(); if (rotated !== source) rotated.recycle() }
        return TensorImage(values,intArrayOf(1,3,48,320),320,48,1f,1f)
    }
    private fun rotate(source: Bitmap): Bitmap = Bitmap.createBitmap(source,0,0,source.width,source.height,Matrix().apply{postRotate(90f)},true)
}

private object FaceTensor {
    fun detector(source: Bitmap): TensorImage {
        val scale=min(640f/source.width,640f/source.height); val w=(source.width*scale).roundToInt(); val h=(source.height*scale).roundToInt()
        val resized=Bitmap.createScaledBitmap(source,w,h,true); val padded=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888)
        Canvas(padded).drawBitmap(resized,0f,0f,null)
        val values=channels(padded,floatArrayOf(0f,0f,0f),floatArrayOf(1f/255f,1f/255f,1f/255f),bgr=true, raw=true)
        resized.recycle(); padded.recycle()
        return TensorImage(values,intArrayOf(1,3,640,640),640,640,scale,scale)
    }
    fun recognizer(source: Bitmap): FloatArray = channels(source,floatArrayOf(0f,0f,0f),floatArrayOf(1f,1f,1f),bgr=false, raw=true)
}

private fun channels(bitmap: Bitmap, mean: FloatArray, std: FloatArray, bgr: Boolean, raw: Boolean=false): FloatArray {
    val plane=bitmap.width*bitmap.height; val values=FloatArray(3*plane)
    for(y in 0 until bitmap.height) for(x in 0 until bitmap.width){ val c=bitmap.getPixel(x,y); val rgb=floatArrayOf((c shr 16 and 255).toFloat(),(c shr 8 and 255).toFloat(),(c and 255).toFloat()); val at=y*bitmap.width+x
        for(ch in 0..2){ val src=if(bgr) 2-ch else ch; values[ch*plane+at]=if(raw) rgb[src] else (rgb[src]/255f-mean[ch])/std[ch] } }
    return values
}

object DbRegions {
    /** Bounded connected components over DB probability map; returned boxes are normalized to the source. */
    fun rectangles(probability: FloatArray, width: Int, height: Int, sourceWidth: Int, sourceHeight: Int): List<FaceBox> {
        if (probability.size != width*height) return emptyList()
        val seen=BooleanArray(probability.size); val regions=mutableListOf<FaceBox>()
        for(seed in probability.indices) if(!seen[seed] && probability[seed]>=.3f){
            var minX=seed%width; var maxX=minX; var minY=seed/width; var maxY=minY; var score=0f; var count=0
            val queue=java.util.ArrayDeque<Int>(); queue.add(seed); seen[seed]=true
            while(queue.isNotEmpty() && count<200000){ val at=queue.removeFirst(); val x=at%width; val y=at/width; minX=min(minX,x);maxX=max(maxX,x);minY=min(minY,y);maxY=max(maxY,y);score+=probability[at];count++
                intArrayOf(at-1,at+1,at-width,at+width).forEach { n -> if(n in probability.indices && !seen[n] && probability[n]>=.3f && abs(n%width-x)<=1){seen[n]=true;queue.add(n)} } }
            if(count>=4 && score/count>=.6f){ val pad=max(1,((maxX-minX+maxY-minY)*.125f).roundToInt()); regions += FaceBox(((minX-pad).coerceAtLeast(0)/width.toFloat()),((minY-pad).coerceAtLeast(0)/height.toFloat()),((maxX+pad+1).coerceAtMost(width)/width.toFloat()),((maxY+pad+1).coerceAtMost(height)/height.toFloat())) }
        }
        return regions.sortedWith(compareBy<FaceBox>{it.top}.thenBy{it.left}).take(1000)
    }
}

private data class FaceProposal(val box: FaceBox,val landmarks:FloatArray,val confidence:Float)
private object YuNetPostprocess {
    fun decode(flat:FloatArray,scaleX:Float,scaleY:Float,width:Int,height:Int):List<FaceProposal>{
        val counts=intArrayOf(6400,1600,400); val strides=intArrayOf(8,16,32); var offset=0
        fun take(count:Int)=flat.copyOfRange(offset,offset+count).also{offset+=count}
        val cls=counts.map(::take); val obj=counts.map(::take)
        val boxes=counts.map{take(it*4)}; val keys=counts.map{take(it*10)}
        require(offset==flat.size)
        val out=mutableListOf<FaceProposal>()
        counts.indices.forEach{level-> val grid=640/strides[level]; for(i in 0 until counts[level]){ val confidence=sqrt(cls[level][i].coerceIn(0f,1f)*obj[level][i].coerceIn(0f,1f)); if(confidence<.9f) continue
            val gx=i%grid; val gy=i/grid; val b=i*4; val cx=(gx+boxes[level][b])*strides[level]/scaleX; val cy=(gy+boxes[level][b+1])*strides[level]/scaleY; val w=exp(boxes[level][b+2])*strides[level]/scaleX; val h=exp(boxes[level][b+3])*strides[level]/scaleY
            val left=((cx-w/2)/width).coerceIn(0f,.999f);val top=((cy-h/2)/height).coerceIn(0f,.999f);val right=((cx+w/2)/width).coerceIn(left+.0001f,1f);val bottom=((cy+h/2)/height).coerceIn(top+.0001f,1f)
            val landmarks=FloatArray(10){k-> val axis=k%2; val gridValue=if(axis==0) gx else gy; val scale=if(axis==0) scaleX else scaleY; (gridValue+keys[level][i*10+k])*strides[level]/scale }
            out+=FaceProposal(FaceBox(left,top,right,bottom),landmarks,confidence)
        }}
        return nms(out.sortedByDescending{it.confidence},.3f).take(100)
    }
    private fun nms(input:List<FaceProposal>,threshold:Float):List<FaceProposal>{val kept=mutableListOf<FaceProposal>();input.forEach{p->if(kept.none{iou(it.box,p.box)>threshold})kept+=p};return kept}
    private fun iou(a:FaceBox,b:FaceBox):Float{val overlap=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top));val area=(a.right-a.left)*(a.bottom-a.top)+(b.right-b.left)*(b.bottom-b.top)-overlap;return if(area<=0)0f else overlap/area}
}

private object FaceAlignment {
    fun align(source:Bitmap, landmarks:FloatArray):Bitmap {
        require(landmarks.size==10)
        val output=Bitmap.createBitmap(112,112,Bitmap.Config.ARGB_8888); val matrix=Matrix()
        matrix.setPolyToPoly(floatArrayOf(landmarks[0],landmarks[1],landmarks[2],landmarks[3]),0,floatArrayOf(38.2946f,51.6963f,73.5318f,51.5014f),0,2)
        Canvas(output).apply{drawColor(Color.BLACK);drawBitmap(source,matrix,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))}
        return output
    }
}
