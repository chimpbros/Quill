package com.yugentech.quill.aira.util
import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import com.yugentech.quill.aira.provider.DirectAiGateway
import com.yugentech.quill.domain.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
class VoiceOutputManager(private val gateway:DirectAiGateway,private val context:Context):SpeechProvider {
 private var tts:TextToSpeech?=null
 private var player:MediaPlayer?=null
 private var audioFile:File?=null
 private var requestJob:Job?=null
 private var pendingText:String?=null
 val error=MutableStateFlow<String?>(null)
 override suspend fun speak(text:String) {
  stop();requestJob=currentCoroutineContext()[Job];error.value=null
  try {
   if(!gateway.settings.cloudSpeech) { androidSpeech(text);return }
   val bytes=gateway.provider.speech(text,gateway.settings.voice)
   currentCoroutineContext().ensureActive()
   val file=withContext(Dispatchers.IO) { File.createTempFile("speech-",".mp3",context.cacheDir).apply { writeBytes(bytes) } }
   audioFile=file
   withContext(Dispatchers.Main) {
    player=MediaPlayer().apply {
     setDataSource(file.absolutePath)
     setOnCompletionListener { it.release();player=null;audioFile?.delete();audioFile=null }
     setOnErrorListener { media,_,_->media.release();player=null;audioFile?.delete();audioFile=null;pendingText=text;error.value="Cloud speech playback failed. Use Android voice instead?";true }
     setOnPreparedListener { it.start() };prepareAsync()
    }
   }
  } catch(e:CancellationException) { throw e }
  catch(_:Exception) { pendingText=text;error.value="Read-aloud failed. Check your voice connection or use Android voice instead." }
  finally { requestJob=null }
 }
 private suspend fun androidSpeech(text:String)=withContext(Dispatchers.Main) {
  if(tts==null) {
   val ready=CompletableDeferred<Int>()
   tts=TextToSpeech(context) { ready.complete(it) }
   if(withTimeout(10000) { ready.await() }!=TextToSpeech.SUCCESS) throw AiException(AiErrorKind.PROVIDER,"Android speech is unavailable. Install a voice in Android settings.")
  }
  for((index,chunk) in text.chunked(TextToSpeech.getMaxSpeechInputLength()).withIndex()) {
   if(tts!!.speak(chunk,if(index==0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,null,"quill-personal-$index")==TextToSpeech.ERROR) throw AiException(AiErrorKind.PROVIDER,"Android speech could not start.")
  }
 }
 suspend fun useAndroidVoice() { val text=pendingText ?: return;error.value=null;try { androidSpeech(text) } catch(_:Exception) { error.value="Android voice is unavailable. Check installed speech voices." } }
 fun dismissError() { error.value=null;pendingText=null }
 override fun stop() { requestJob?.cancel();requestJob=null;tts?.stop();player?.release();player=null;audioFile?.delete();audioFile=null }
 override fun close() { stop();tts?.shutdown();tts=null }
}
