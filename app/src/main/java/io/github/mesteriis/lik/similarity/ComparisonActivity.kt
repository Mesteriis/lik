package io.github.mesteriis.lik.similarity

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import android.os.Bundle
import android.view.View
import android.widget.*
import io.github.mesteriis.lik.R
import io.github.mesteriis.lik.catalog.MediaRecord
import io.github.mesteriis.lik.catalog.MediaSource
import io.github.mesteriis.lik.imports.PhotoLibrary
import io.github.mesteriis.lik.ui.applySystemBarInsets

class ComparisonActivity:Activity(){
    @Volatile private var request=0L
    private lateinit var repository:SimilarityRepository
    override fun onCreate(state:Bundle?){super.onCreate(state);setContentView(R.layout.activity_comparison);findViewById<View>(R.id.comparison_root).applySystemBarInsets();repository=SimilarityRepository(this);findViewById<View>(R.id.comparison_back).setOnClickListener{finish()};load()}
    private fun load(){val left=intent.getStringExtra(EXTRA_LEFT).orEmpty();val right=intent.getStringExtra(EXTRA_RIGHT).orEmpty();val expected=++request
        Thread({val pair=repository.pair(left,right);val images=pair?.let{arrayOf(runCatching{decode(it.left.media)}.getOrNull(),runCatching{decode(it.right.media)}.getOrNull())};val fresh=pair?.let{repository.pair(it.relation.leftMediaId,it.relation.rightMediaId)};runOnUiThread{if(expected!=request||isDestroyed){images?.forEach{it?.recycle()};return@runOnUiThread};if(fresh==null||images?.any{it==null}!=false||!SimilarityPublicationGuard.visible(io.github.mesteriis.lik.catalog.MediaDatabase.get(this),fresh.relation)){unavailable();images?.forEach{it?.recycle()};return@runOnUiThread};show(fresh,requireNotNull(images[0]),requireNotNull(images[1]))}},"lik-comparison-load").start()
    }
    private fun clearImages(){listOf(R.id.comparison_left_image,R.id.comparison_right_image).forEach{id->val image=findViewById<ImageView>(id);(image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.takeUnless{it.isRecycled}?.recycle();image.setImageDrawable(null)}}
    private fun unavailable(){clearImages();findViewById<View>(R.id.comparison_content).visibility=View.GONE;findViewById<View>(R.id.comparison_unavailable).visibility=View.VISIBLE}
    private fun show(pair:ComparisonPair,left:Bitmap,right:Bitmap){
        clearImages()
        findViewById<View>(R.id.comparison_unavailable).visibility=View.GONE;findViewById<View>(R.id.comparison_content).visibility=View.VISIBLE
        findViewById<ImageView>(R.id.comparison_left_image).setImageBitmap(left);findViewById<ImageView>(R.id.comparison_right_image).setImageBitmap(right)
        findViewById<TextView>(R.id.comparison_reason).text=getString(if(pair.relation.kind==SimilarityKind.EXACT)R.string.similarity_exact_reason else R.string.similarity_visual_reason,pair.relation.distance)
        bind(pair.left,R.id.comparison_left_details,R.id.comparison_left_delete);bind(pair.right,R.id.comparison_right_details,R.id.comparison_right_delete)
    }
    private fun bind(photo:ComparisonPhoto,details:Int,delete:Int){findViewById<TextView>(details).text=details(photo.media);findViewById<Button>(delete).apply{visibility=if(photo.capabilities.canMoveToTrash)View.VISIBLE else View.GONE;setOnClickListener{confirmTrash(photo.media.mediaId)}}}
    private fun details(row:MediaRecord):String=getString(R.string.similarity_photo_details,row.displayName?:row.mediaId.takeLast(12),getString(if(row.source==MediaSource.DEVICE)R.string.similarity_source_device else R.string.similarity_source_import),row.width?:0,row.height?:0,(row.byteSize?:0)/1048576.0,getString(R.string.similarity_available))
    private fun confirmTrash(id:String){AlertDialog.Builder(this).setTitle(R.string.similarity_trash_title).setMessage(R.string.similarity_trash_message).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.delete){_,_->Thread({repository.moveImportedToTrash(id);runOnUiThread{load()}},"lik-comparison-trash").start()}.show()}
    private fun decode(row:MediaRecord):Bitmap{val source=if(row.source==MediaSource.GOOGLE_IMPORT)ImageDecoder.createSource(PhotoLibrary.store(this).fileFor(requireNotNull(row.privateFileId)))else ImageDecoder.createSource(contentResolver,requireNotNull(row.contentUri).toUri());return ImageDecoder.decodeBitmap(source){decoder,info,_->val scale=minOf(1.0,512.0/maxOf(info.size.width,info.size.height));decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;decoder.setOnPartialImageListener{false}}}
    override fun onDestroy(){request++;clearImages();super.onDestroy()}
    companion object{const val EXTRA_LEFT="left_media_id";const val EXTRA_RIGHT="right_media_id"}
}
