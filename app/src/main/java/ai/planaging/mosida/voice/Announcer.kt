package ai.planaging.mosida.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener

/**
 * 기사가 화면을 보지 않아도 되도록 짧게 소리로 알린다.
 *
 * 1순위: Pleos TextToSpeech SDK (ON_DEVICE, 키 불필요). Pleos 차량·에뮬레이터에 들어 있는 음성 엔진.
 * 2순위: 안드로이드 기본 TTS (한국어 음성이 있는 기기).
 * 둘 다 안 되면 조용히 넘어간다. 화면 표시는 그대로 동작.
 */
class Announcer(context: Context) {

    private val appContext = context.applicationContext
    private var pleos: PleosTts? = null
    private var pleosReady = false
    private var android: TextToSpeech? = null
    private var androidReady = false
    /** 초기화 전에 들어온 마지막 문장 하나만 기억했다가 준비되면 읽는다 */
    private var pending: String? = null

    init {
        // 음성은 부가 기능이다. 어떤 이유로든 Pleos 쪽이 실패하면 앱을 멈추지 말고 안드로이드 TTS로 넘어간다.
        runCatching { startPleos() }.onFailure {
            Log.w(TAG, "Pleos TTS setup failed: ${it.message}")
            runCatching { pleos?.release() }
            pleos = null
            startAndroid()
        }
    }

    private fun startPleos() {
        val tts = runCatching { PleosTts(appContext, Mode.ON_DEVICE) }.getOrElse {
            Log.w(TAG, "Pleos TTS unavailable: ${it.message}")
            startAndroid()
            return
        }
        pleos = tts
        // SDK는 initialize 성공 뒤에 리스너·언어 설정을 받는다.
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                runCatching {
                    tts.addEventListener(object : EventListener {
                        override fun onStart() {}
                        override fun onReady() {}
                        override fun onDone() {}
                        override fun onStop() {}
                        override fun onUpdatedRms(rms: Double) {}
                        override fun onError(type: ErrorType) {
                            Log.w(TAG, "Pleos TTS error: $type")
                        }
                    })
                }
                // 엔진은 "ko-KR" 형식을 요구한다 (Locale.KOREAN은 "ko-"로 넘어가 unsupported locale).
                runCatching { tts.setLocale(Locale.KOREA) }
                pleosReady = true
                Log.i(TAG, "Pleos TTS ready (ON_DEVICE)")
                flush()
            }

            override fun onFailure(type: ErrorType) {
                Log.w(TAG, "Pleos TTS init failed: $type -> Android TTS")
                runCatching { tts.release() }
                pleos = null
                startAndroid()
            }
        })
    }

    private fun startAndroid() {
        android = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "Android TTS init failed: $status")
                return@TextToSpeech
            }
            val r = android?.setLanguage(Locale.KOREAN)
            androidReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            if (!androidReady) Log.w(TAG, "Korean Android TTS not available: $r")
            flush()
        }
    }

    private fun flush() {
        pending?.let { pending = null; say(it) }
    }

    fun say(text: String) {
        when {
            pleosReady -> runCatching { pleos?.stop(); pleos?.speak(text, true) }
                .onFailure { Log.w(TAG, "Pleos speak failed: ${it.message}") }
            androidReady -> android?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mosida")
            else -> pending = text
        }
        Log.i(TAG, "say: $text")
    }

    fun release() {
        runCatching { pleos?.stop(); pleos?.release() }
        android?.run { stop(); shutdown() }
    }

    companion object {
        private const val TAG = "MosidaVoice"
    }
}
