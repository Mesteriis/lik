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
        val boxes = DbRegions.quadrilaterals(
            probability, resized.width, resized.height,
            resized.sourceWidth, resized.sourceHeight, resized.paddedWidth, resized.paddedHeight,
        )
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

private data class TensorImage(
    val values: FloatArray, val shape: IntArray, val width: Int, val height: Int,
    val scaleX: Float, val scaleY: Float,
    val sourceWidth: Int = width, val sourceHeight: Int = height,
    val paddedWidth: Int = sourceWidth, val paddedHeight: Int = sourceHeight,
)

data class OcrResizePlan(
    val paddedWidth: Int, val paddedHeight: Int,
    val resizedWidth: Int, val resizedHeight: Int,
) {
    companion object {
        fun forSource(width: Int, height: Int): OcrResizePlan {
            require(width > 0 && height > 0)
            val paddedWidth = if (width + height < 64) max(width, 32) else width
            val paddedHeight = if (width + height < 64) max(height, 32) else height
            val ratio = 960f / max(paddedWidth, paddedHeight)
            fun publisherDimension(value: Int): Int {
                val truncated = (value * ratio).toInt().coerceAtLeast(1)
                return ((truncated + 127) / 128) * 128
            }
            return OcrResizePlan(paddedWidth, paddedHeight, publisherDimension(paddedWidth), publisherDimension(paddedHeight))
        }
    }
}

private object OcrTensor {
    fun detector(source: Bitmap): TensorImage {
        val plan = OcrResizePlan.forSource(source.width, source.height)
        val padded = if (plan.paddedWidth == source.width && plan.paddedHeight == source.height) source else
            Bitmap.createBitmap(plan.paddedWidth, plan.paddedHeight, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(Color.BLACK); drawBitmap(source, 0f, 0f, null) }
            }
        val bitmap = Bitmap.createScaledBitmap(padded, plan.resizedWidth, plan.resizedHeight, true)
        return try {
            TensorImage(
                channels(bitmap, floatArrayOf(.485f,.456f,.406f), floatArrayOf(.229f,.224f,.225f), bgr=true),
                intArrayOf(1,3,plan.resizedHeight,plan.resizedWidth), plan.resizedWidth, plan.resizedHeight,
                plan.resizedWidth/plan.paddedWidth.toFloat(), plan.resizedHeight/plan.paddedHeight.toFloat(),
                source.width, source.height, plan.paddedWidth, plan.paddedHeight,
            )
        } finally {
            if (bitmap !== padded) bitmap.recycle()
            if (padded !== source) padded.recycle()
        }
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
    /** Pinned contracts-v1 DB postprocess: contour, min-area quad, polygon score and polygon offset. */
    fun quadrilaterals(
        probability: FloatArray, width: Int, height: Int,
        sourceWidth: Int = width, sourceHeight: Int = height,
        paddedWidth: Int = sourceWidth, paddedHeight: Int = sourceHeight,
    ): List<OcrQuad> {
        if (probability.size != width*height) return emptyList()
        val seen=BooleanArray(probability.size); val regions=mutableListOf<OcrQuad>()
        var contours = 0
        for(seed in probability.indices) if(!seen[seed] && probability[seed]>=.3f && contours++ < 1000){
            val pixels=ArrayList<OcrPoint>()
            val queue=java.util.ArrayDeque<Int>(); queue.add(seed); seen[seed]=true
            while(queue.isNotEmpty() && pixels.size<200000){ val at=queue.removeFirst(); val x=at%width; val y=at/width; pixels += OcrPoint(x+.5f,y+.5f)
                for(dy in -1..1)for(dx in -1..1){if(dx==0&&dy==0)continue;val nx=x+dx;val ny=y+dy;if(nx in 0 until width&&ny in 0 until height){val n=ny*width+nx;if(!seen[n]&&probability[n]>=.3f){seen[n]=true;queue.add(n)}}} }
            if(pixels.size < 4) continue
            val raw = minimumRectangle(pixels)?.let(::order) ?: continue
            if(shortSide(raw)<3f) continue
            val score=polygonScore(probability,width,height,raw)
            if(score<.6f) continue
            val expandedPolygon=offsetConvex(raw,if(perimeter(raw)==0f)0f else polygonArea(raw)*1.5f/perimeter(raw)) ?: continue
            val expanded=minimumRectangle(expandedPolygon)?.let(::order) ?: continue
            if(shortSide(expanded)<5f) continue
            val normalized=expanded.map { point ->
                val sourceX=(point.x.coerceIn(0f,width.toFloat())/width*paddedWidth).coerceIn(0f,sourceWidth.toFloat())
                val sourceY=(point.y.coerceIn(0f,height.toFloat())/height*paddedHeight).coerceIn(0f,sourceHeight.toFloat())
                OcrPoint(sourceX/sourceWidth,sourceY/sourceHeight)
            }
            if(shortSide(normalized)>0f) regions += OcrQuad(order(normalized),score)
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
    internal fun polygonScoreForTests(probability:FloatArray,width:Int,height:Int,points:List<OcrPoint>)=polygonScore(probability,width,height,points)
    private fun polygonScore(probability:FloatArray,width:Int,height:Int,points:List<OcrPoint>):Float {
        val minX=floor(points.minOf{it.x}).toInt().coerceIn(0,width-1);val maxX=ceil(points.maxOf{it.x}).toInt().coerceIn(0,width-1)
        val minY=floor(points.minOf{it.y}).toInt().coerceIn(0,height-1);val maxY=ceil(points.maxOf{it.y}).toInt().coerceIn(0,height-1)
        val local=points.map{OcrPoint((it.x-minX).toInt().toFloat(),(it.y-minY).toInt().toFloat())}
        val maskWidth=maxX-minX+1;val mask=fillPolyLine8(maskWidth,maxY-minY+1,local)
        var sum=0.0;var count=0
        for(y in minY..maxY)for(x in minX..maxX)if(mask[(y-minY)*maskWidth+x-minX]){sum+=probability[y*width+x];count++}
        return if(count==0)0f else (sum/count).toFloat()
    }
    internal fun fillPolyLine8ForTests(width:Int,height:Int,points:List<OcrPoint>)=fillPolyLine8(width,height,points)

    /**
     * Port of OpenCV 4.10.0 `drawing.cpp` CollectPolyEdges/FillEdgeCollection and LineIterator
     * for one convex LINE_8 polygon. The pinned source is Apache-2.0 and retains its Intel
     * permissive notice: https://github.com/opencv/opencv/blob/4.10.0/modules/imgproc/src/drawing.cpp
     */
    private fun fillPolyLine8(width:Int,height:Int,points:List<OcrPoint>):BooleanArray {
        require(width>0&&height>0);val mask=BooleanArray(width*height);if(points.isEmpty())return mask
        val vertices=points.map{IntPoint(it.x.toInt(),it.y.toInt())};val edges=mutableListOf<ScanEdge>();var previous=vertices.last()
        vertices.forEach{current->
            drawLine8(mask,width,height,previous,current)
            var p0x=previous.x.toLong() shl XY_SHIFT;var p1x=current.x.toLong() shl XY_SHIFT
            var p0y=previous.y.toLong();var p1y=current.y.toLong()
            if(!insideImage(previous,width,height)||!insideImage(current,width,height)){
                val clipped=clipLine(width,height,previous,current)
                if(clipped.first.y!=clipped.second.y){p0x=clipped.first.x shl XY_SHIFT;p1x=clipped.second.x shl XY_SHIFT;p0y=clipped.first.y;p1y=clipped.second.y}
            }else{p0x+=XY_HALF;p1x+=XY_HALF}
            if(previous.y!=current.y){val dx=(p1x-p0x)/(p1y-p0y);if(previous.y<current.y)edges+=ScanEdge(previous.y,current.y,p0x+(previous.y-p0y)*dx,dx)else edges+=ScanEdge(current.y,previous.y,p1x+(current.y-p1y)*dx,dx)}
            previous=current
        }
        if(edges.size<2)return mask
        val yMin=edges.minOf{it.y0};val yMax=min(edges.maxOf{it.y1},height)
        for(y in yMin until yMax){val active=edges.filter{it.y0<=y&&y<it.y1}.sortedWith(compareBy<ScanEdge>{it.x}.thenBy{it.dx})
            for(at in 0 until active.lastIndex step 2){var x1=(active[at].x shr XY_SHIFT).toInt();var x2=(active[at+1].x shr XY_SHIFT).toInt();if(x1>x2){val swap=x1;x1=x2;x2=swap};if(y>=0&&x1<width&&x2>=0){x1=max(0,x1);x2=min(width-1,x2);for(x in x1..x2)mask[y*width+x]=true}}
            active.forEach{it.x+=it.dx}
        }
        return mask
    }
    private fun drawLine8(mask:BooleanArray,width:Int,height:Int,start:IntPoint,end:IntPoint){
        val clipped=clipLine(width,height,start,end);if(!clipped.accepted)return
        var x=clipped.first.x.toInt();var y=clipped.first.y.toInt();val endX=clipped.second.x.toInt();val endY=clipped.second.y.toInt()
        var dx=endX-x;var dy=endY-y;var deltaX=1;var deltaY=1
        if(dx<0){dx=-dx;dy=-dy;x=endX;y=endY}
        if(dy<0){dy=-dy;deltaY=-1}
        val vertical=dy>dx
        if(vertical){val swap=dx;dx=dy;dy=swap;val step=deltaX;deltaX=deltaY;deltaY=step}
        var error=dx-2*dy;val plusDelta=2*dx;val minusDelta=-2*dy;var minusShift=deltaX;var plusShift=0;var minusStep=0;var plusStep=deltaY
        if(vertical){var swap=plusStep;plusStep=plusShift;plusShift=swap;swap=minusStep;minusStep=minusShift;minusShift=swap}
        repeat(dx+1){mask[y*width+x]=true;val negative=error<0;error+=minusDelta+if(negative)plusDelta else 0;x+=minusShift+if(negative)plusShift else 0;y+=minusStep+if(negative)plusStep else 0}
    }
    private fun clipLine(width:Int,height:Int,start:IntPoint,end:IntPoint):ClippedLine{
        var x1=start.x.toLong();var y1=start.y.toLong();var x2=end.x.toLong();var y2=end.y.toLong();val right=width-1L;val bottom=height-1L
        fun code(x:Long,y:Long):Int{return (if(x<0)1 else 0)+(if(x>right)2 else 0)+(if(y<0)4 else 0)+(if(y>bottom)8 else 0)}
        var c1=code(x1,y1);var c2=code(x2,y2)
        if((c1 and c2)==0&&(c1 or c2)!=0){
            var a:Long
            if((c1 and 12)!=0){a=if(c1<8)0L else bottom;x1+=((a-y1).toDouble()*(x2-x1)/(y2-y1)).toLong();y1=a;c1=code(x1,y1) and 3}
            if((c2 and 12)!=0){a=if(c2<8)0L else bottom;x2+=((a-y2).toDouble()*(x2-x1)/(y2-y1)).toLong();y2=a;c2=code(x2,y2) and 3}
            if((c1 and c2)==0&&(c1 or c2)!=0){
                if(c1!=0){a=if(c1==1)0L else right;y1+=((a-x1).toDouble()*(y2-y1)/(x2-x1)).toLong();x1=a;c1=0}
                if(c2!=0){a=if(c2==1)0L else right;y2+=((a-x2).toDouble()*(y2-y1)/(x2-x1)).toLong();x2=a;c2=0}
            }
        }
        return ClippedLine((c1 or c2)==0,LongPoint(x1,y1),LongPoint(x2,y2))
    }
    private fun insideImage(point:IntPoint,width:Int,height:Int)=point.x in 0 until width&&point.y in 0 until height
    private data class IntPoint(val x:Int,val y:Int);private data class LongPoint(val x:Long,val y:Long);private data class ClippedLine(val accepted:Boolean,val first:LongPoint,val second:LongPoint);private data class ScanEdge(val y0:Int,val y1:Int,var x:Long,val dx:Long)
    private const val XY_SHIFT=16;private const val XY_HALF=1L shl (XY_SHIFT-1)
    private fun polygonArea(points:List<OcrPoint>)=abs(points.indices.sumOf{i->val a=points[i];val b=points[(i+1)%points.size];(a.x*b.y-a.y*b.x).toDouble()}/2).toFloat()
    private fun perimeter(points:List<OcrPoint>)=points.indices.sumOf{i->val a=points[i];val b=points[(i+1)%points.size];hypot(a.x-b.x,a.y-b.y).toDouble()}.toFloat()
    private fun shortSide(points:List<OcrPoint>)=points.indices.minOf{i->val a=points[i];val b=points[(i+1)%points.size];hypot(a.x-b.x,a.y-b.y)}
    private data class OffsetLine(val point:OcrPoint,val direction:OcrPoint)
    private fun offsetConvex(points:List<OcrPoint>,distance:Float):List<OcrPoint>? {
        if(distance<=0f)return null
        val ordered=order(points);val signed=ordered.indices.sumOf{i->val a=ordered[i];val b=ordered[(i+1)%ordered.size];(a.x*b.y-a.y*b.x).toDouble()}
        val lines=ordered.indices.map{i->val a=ordered[i];val b=ordered[(i+1)%ordered.size];val dx=b.x-a.x;val dy=b.y-a.y;val length=hypot(dx,dy);if(length==0f)return null
            val sign=if(signed>=0)1f else -1f;val nx=sign*dy/length;val ny=-sign*dx/length;OffsetLine(OcrPoint(a.x+nx*distance,a.y+ny*distance),OcrPoint(dx,dy))}
        return lines.indices.map{i->intersect(lines[(i+lines.size-1)%lines.size],lines[i])?:return null}
    }
    private fun intersect(a:OffsetLine,b:OffsetLine):OcrPoint? {val cross=a.direction.x*b.direction.y-a.direction.y*b.direction.x;if(abs(cross)<1e-5f)return null;val dx=b.point.x-a.point.x;val dy=b.point.y-a.point.y;val t=(dx*b.direction.y-dy*b.direction.x)/cross;return OcrPoint(a.point.x+t*a.direction.x,a.point.y+t*a.direction.y)}
    private fun order(points:List<OcrPoint>):List<OcrPoint>{
        val byX=points.sortedWith(compareBy<OcrPoint>{it.x}.thenBy{it.y});val left=byX.take(2).sortedBy{it.y};val right=byX.takeLast(2).sortedBy{it.y}
        return listOf(left.first(),right.first(),right.last(),left.last())
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
