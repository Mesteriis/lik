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

data class OcrPoint(val x: Float, val y: Float)
data class OcrQuad(val points: List<OcrPoint>, val score: Float) {
    init { require(points.size == 4 && points.all { it.x in 0f..1f && it.y in 0f..1f }) }
    val box: FaceBox get() = FaceBox(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
}
data class OcrRegion(val quad: OcrQuad, val text: String, val confidence: Float) { val box get() = quad.box }
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
        val probability = runtime.runFloat(file(detector), "x", resized.shape, resized.values, "fetch_name_0",
            outputCapacityFloats = resized.width * resized.height).getOrThrow()
        val boxes = DbRegions.quadrilaterals(probability, resized.width, resized.height)
        val dictionary = file("ocr-cyrillic-rec-v1/characters.txt").readLines(Charsets.UTF_8) + " "
        val regions = boxes.mapNotNull { quad ->
            val crop = crop(bitmap, quad)
            try {
                val tensor = OcrTensor.recognizer(crop)
                val flat = runtime.runFloat(file("ocr-cyrillic-rec-v1/model.onnx"), "x", tensor.shape, tensor.values, "fetch_name_0").getOrThrow()
                if (flat.size % 852 != 0) error("OCR_OUTPUT_SHAPE")
                val decoded = CtcDecoder.decode(Array(flat.size / 852) { at -> flat.copyOfRange(at * 852, (at + 1) * 852) }, dictionary)
                decoded.takeIf { it.text.isNotBlank() }?.let { OcrRegion(quad, it.text, it.confidence) }
            } finally { crop.recycle() }
        }.sortedWith(compareBy<OcrRegion> { it.box.top }.thenBy { it.box.left })
        return OcrInference(regions)
    }

    fun peopleBitmap(bitmap: Bitmap): List<FaceInference> {
        val tensor = FaceTensor.detector(bitmap)
        val names = listOf("cls_8","cls_16","cls_32","obj_8","obj_16","obj_32","bbox_8","bbox_16","bbox_32","kps_8","kps_16","kps_32")
        val flat = runtime.runFloatMulti(file("yunet-v1/model.onnx"), "input", intArrayOf(1,3,640,640), tensor.values, names,
            outputCapacityFloats = 134_400).getOrThrow()
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
    internal fun crop(bitmap: Bitmap, quad: OcrQuad): Bitmap {
        val points = quad.points.map { OcrPoint(it.x * bitmap.width, it.y * bitmap.height) }
        fun distance(a: OcrPoint, b: OcrPoint) = hypot(a.x - b.x, a.y - b.y)
        val width = ceil(max(distance(points[0], points[1]), distance(points[3], points[2]))).toInt().coerceAtLeast(1)
        val height = ceil(max(distance(points[0], points[3]), distance(points[1], points[2]))).toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val source = points.flatMap { listOf(it.x, it.y) }.toFloatArray()
        val target = floatArrayOf(0f,0f,width.toFloat(),0f,width.toFloat(),height.toFloat(),0f,height.toFloat())
        val matrix = Matrix().apply { require(setPolyToPoly(source, 0, target, 0, 4)) }
        Canvas(output).apply { drawColor(Color.WHITE); drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
        return output
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
    /** Pinned DB postprocess: threshold, component contour, box score, unclip and ordered quadrilateral. */
    fun quadrilaterals(probability: FloatArray, width: Int, height: Int): List<OcrQuad> {
        if (probability.size != width*height) return emptyList()
        val seen=BooleanArray(probability.size); val regions=mutableListOf<OcrQuad>()
        for(seed in probability.indices) if(!seen[seed] && probability[seed]>=.3f){
            var score=0f; val pixels=ArrayList<OcrPoint>()
            val queue=java.util.ArrayDeque<Int>(); queue.add(seed); seen[seed]=true
            while(queue.isNotEmpty() && pixels.size<200000){ val at=queue.removeFirst(); val x=at%width; val y=at/width; pixels += OcrPoint(x+.5f,y+.5f);score+=probability[at]
                intArrayOf(at-1,at+1,at-width,at+width).forEach { n -> if(n in probability.indices && !seen[n] && probability[n]>=.3f && abs(n%width-x)<=1){seen[n]=true;queue.add(n)} } }
            val mean=if(pixels.isEmpty())0f else score/pixels.size
            if(pixels.size>=4 && mean>=.6f) minimumRectangle(pixels)?.let { raw ->
                val expanded=unclip(raw,1.5f).map { OcrPoint((it.x/width).coerceIn(0f,1f),(it.y/height).coerceIn(0f,1f)) }
                if(expanded.map{it.x}.distinct().size>1&&expanded.map{it.y}.distinct().size>1) regions += OcrQuad(order(expanded),mean)
            }
        }
        return regions.sortedWith(compareBy<OcrQuad>{it.box.top}.thenBy{it.box.left}).take(1000)
    }

    @Deprecated("Use quadrilaterals")
    fun rectangles(probability: FloatArray,width:Int,height:Int,sourceWidth:Int,sourceHeight:Int)=quadrilaterals(probability,width,height).map(OcrQuad::box)

    private fun minimumRectangle(points:List<OcrPoint>):List<OcrPoint>?{
        val hull=convexHull(points);if(hull.size<2)return null
        var best:List<OcrPoint>?=null;var area=Float.POSITIVE_INFINITY
        for(i in hull.indices){val a=hull[i];val b=hull[(i+1)%hull.size];val angle=-atan2(b.y-a.y,b.x-a.x);val c=cos(angle);val s=sin(angle)
            var minX=Float.POSITIVE_INFINITY;var minY=Float.POSITIVE_INFINITY;var maxX=Float.NEGATIVE_INFINITY;var maxY=Float.NEGATIVE_INFINITY
            hull.forEach{p->val x=p.x*c-p.y*s;val y=p.x*s+p.y*c;minX=min(minX,x);maxX=max(maxX,x);minY=min(minY,y);maxY=max(maxY,y)}
            val candidateArea=(maxX-minX)*(maxY-minY);if(candidateArea<area){area=candidateArea;val corners=listOf(OcrPoint(minX,minY),OcrPoint(maxX,minY),OcrPoint(maxX,maxY),OcrPoint(minX,maxY));best=corners.map{p->OcrPoint(p.x*c+p.y*s,-p.x*s+p.y*c)}}}
        return best
    }
    private fun convexHull(input:List<OcrPoint>):List<OcrPoint>{val p=input.distinctBy{"${it.x}:${it.y}"}.sortedWith(compareBy<OcrPoint>{it.x}.thenBy{it.y});if(p.size<=2)return p
        fun cross(o:OcrPoint,a:OcrPoint,b:OcrPoint)=(a.x-o.x)*(b.y-o.y)-(a.y-o.y)*(b.x-o.x)
        val lower=mutableListOf<OcrPoint>();p.forEach{x->while(lower.size>=2&&cross(lower[lower.size-2],lower.last(),x)<=0)lower.removeAt(lower.lastIndex);lower+=x}
        val upper=mutableListOf<OcrPoint>();p.asReversed().forEach{x->while(upper.size>=2&&cross(upper[upper.size-2],upper.last(),x)<=0)upper.removeAt(upper.lastIndex);upper+=x};return lower.dropLast(1)+upper.dropLast(1)}
    private fun unclip(points:List<OcrPoint>,ratio:Float):List<OcrPoint>{val center=OcrPoint(points.map{it.x}.average().toFloat(),points.map{it.y}.average().toFloat());val area=abs(points.indices.sumOf{i->val a=points[i];val b=points[(i+1)%points.size];(a.x*b.y-a.y*b.x).toDouble()}/2).toFloat();val perimeter=points.indices.sumOf{i->val a=points[i];val b=points[(i+1)%points.size];hypot(a.x-b.x,a.y-b.y).toDouble()}.toFloat();val distance=if(perimeter==0f)0f else area*ratio/perimeter;return points.map{p->val r=hypot(p.x-center.x,p.y-center.y);val scale=if(r==0f)1f else (r+distance)/r;OcrPoint(center.x+(p.x-center.x)*scale,center.y+(p.y-center.y)*scale)}}
    private fun order(points:List<OcrPoint>):List<OcrPoint>{val sorted=points.sortedBy{it.y};val top=sorted.take(2).sortedBy{it.x};val bottom=sorted.takeLast(2).sortedByDescending{it.x};return top+bottom}
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
        val output=Bitmap.createBitmap(112,112,Bitmap.Config.ARGB_8888)
        val transform=SimilarityTransform.estimate(landmarks,SFACE_TEMPLATE)
        val matrix=Matrix().apply{setValues(floatArrayOf(transform.a,-transform.b,transform.tx,transform.b,transform.a,transform.ty,0f,0f,1f))}
        Canvas(output).apply{drawColor(Color.BLACK);drawBitmap(source,matrix,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))}
        return output
    }
    private val SFACE_TEMPLATE=floatArrayOf(38.2946f,51.6963f,73.5318f,51.5014f,56.0252f,71.7366f,41.5493f,92.3655f,70.7299f,92.2041f)
}

data class SimilarityTransform(val a:Float,val b:Float,val tx:Float,val ty:Float){fun map(x:Float,y:Float)=OcrPoint(a*x-b*y+tx,b*x+a*y+ty)
    companion object{fun estimate(source:FloatArray,target:FloatArray):SimilarityTransform{require(source.size==10&&target.size==10);val sx=(0 until 5).map{source[it*2]}.average().toFloat();val sy=(0 until 5).map{source[it*2+1]}.average().toFloat();val tx0=(0 until 5).map{target[it*2]}.average().toFloat();val ty0=(0 until 5).map{target[it*2+1]}.average().toFloat();var real=0.0;var imag=0.0;var denom=0.0;for(i in 0 until 5){val x=source[i*2]-sx;val y=source[i*2+1]-sy;val u=target[i*2]-tx0;val v=target[i*2+1]-ty0;real+=x*u+y*v;imag+=x*v-y*u;denom+=x*x+y*y};require(denom>0);val a=(real/denom).toFloat();val b=(imag/denom).toFloat();return SimilarityTransform(a,b,tx0-a*sx+b*sy,ty0-b*sx-a*sy)}}}
