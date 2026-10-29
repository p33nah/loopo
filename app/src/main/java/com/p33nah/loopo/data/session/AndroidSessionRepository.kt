package com.p33nah.loopo.data.session

import android.content.Context
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.p33nah.loopo.domain.model.AudioContainer
import com.p33nah.loopo.domain.model.LoopRegion
import com.p33nah.loopo.domain.model.PlaybackRate
import com.p33nah.loopo.domain.model.SessionLoadOutcome
import com.p33nah.loopo.domain.model.SessionRepository
import com.p33nah.loopo.domain.model.SessionSaveOutcome
import com.p33nah.loopo.domain.model.SessionSource
import com.p33nah.loopo.domain.model.SessionState
import com.p33nah.loopo.domain.waveform.WaveformViewport
import kotlinx.coroutines.flow.first
import org.json.JSONException
import org.json.JSONObject

private val Context.sessionDataStore by preferencesDataStore(name = "study_session")

class AndroidSessionRepository(context: Context) : SessionRepository {
	private val dataStore = context.applicationContext.sessionDataStore

	override suspend fun load(): SessionLoadOutcome = try {
		val json = dataStore.data.first()[SESSION_KEY] ?: return SessionLoadOutcome.Empty
		SessionLoadOutcome.Found(decode(json))
	} catch (_: JSONException) {
		SessionLoadOutcome.Corrupt
	} catch (_: IllegalArgumentException) {
		SessionLoadOutcome.Corrupt
	} catch (_: IOException) {
		SessionLoadOutcome.StorageFailure
	} catch (_: java.io.IOException) {
		SessionLoadOutcome.StorageFailure
	}

	override suspend fun save(session: SessionState): SessionSaveOutcome = try {
		dataStore.edit { preferences -> preferences[SESSION_KEY] = encode(session) }
		SessionSaveOutcome.Saved
	} catch (_: IOException) {
		SessionSaveOutcome.StorageFailure
	} catch (_: java.io.IOException) {
		SessionSaveOutcome.StorageFailure
	}

	private fun encode(session: SessionState): String {
		val source = session.source
		return JSONObject()
			.put("version", session.schemaVersion)
			.put("uri", source.uri)
			.put("name", source.displayName)
			.put("size", source.sizeBytes ?: JSONObject.NULL)
			.put("modified", source.modifiedAtMillis ?: JSONObject.NULL)
			.put("duration", source.durationFrames)
			.put("sampleRate", source.sampleRate)
			.put("channels", source.channelCount)
			.put("container", source.container.name)
			.put("position", session.positionFrame)
			.put("rate", session.playbackRate.percent)
			.put("loopA", session.loop.aFrame)
			.put("loopB", session.loop.bFrame)
			.put("loopEnabled", session.loop.enabled)
			.put("crossfadeMs", session.loop.crossfadeMs)
			.put("viewStart", session.viewport.startFrame)
			.put("viewFrames", session.viewport.visibleFrames)
			.put("updatedAt", session.updatedAtMillis)
			.toString()
	}

	private fun decode(raw: String): SessionState {
		val json = JSONObject(raw)
		val version = json.getInt("version")
		if (version != SessionState.CURRENT_SCHEMA_VERSION) throw IllegalArgumentException("Unsupported session version")
		val duration = json.getLong("duration")
		val source = SessionSource(
			uri = json.getString("uri"), displayName = json.getString("name"),
			sizeBytes = json.optionalLong("size"), modifiedAtMillis = json.optionalLong("modified"),
			durationFrames = duration, sampleRate = json.getInt("sampleRate"),
			channelCount = json.getInt("channels"), container = AudioContainer.valueOf(json.getString("container")),
		)
		val viewport = WaveformViewport(json.getLong("viewStart"), json.getLong("viewFrames"), duration)
		return SessionState(
			source = source,
			positionFrame = json.getLong("position"),
			playbackRate = PlaybackRate.fromPercent(json.getInt("rate")),
			loop = LoopRegion(
				aFrame = json.getLong("loopA"), bFrame = json.getLong("loopB"),
				enabled = json.getBoolean("loopEnabled"), crossfadeMs = json.getInt("crossfadeMs"),
			),
			viewport = viewport,
			updatedAtMillis = json.getLong("updatedAt"),
			schemaVersion = version,
		)
	}

	private fun JSONObject.optionalLong(key: String): Long? =
		if (!has(key) || isNull(key)) null else getLong(key)

	private companion object {
		val SESSION_KEY = stringPreferencesKey("record")
	}
}
