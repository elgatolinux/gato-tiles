package com.gatotiles

import android.app.Application
import com.gatotiles.audio.PreviewPlayer
import com.gatotiles.data.AlbumRepository
import com.gatotiles.data.AlbumRun
import com.gatotiles.data.ArtRepository
import com.gatotiles.data.ChartRepository
import com.gatotiles.data.MusicRepository
import com.gatotiles.data.ResolvedColors
import com.gatotiles.data.ScoreRepository
import com.gatotiles.data.SettingsRepository
import com.gatotiles.data.Song
import com.gatotiles.data.SongColorRepository

/** Holds the app-wide repositories (small app, so a plain service locator). */
class GatoApp : Application() {
    lateinit var settings: SettingsRepository
        private set
    lateinit var music: MusicRepository
        private set
    lateinit var art: ArtRepository
        private set
    lateinit var charts: ChartRepository
        private set
    lateinit var scores: ScoreRepository
        private set
    lateinit var preview: PreviewPlayer
        private set
    lateinit var songColors: SongColorRepository
        private set
    lateinit var albums: AlbumRepository
        private set

    /** The album being played song after song, if any. */
    var run: AlbumRun? = null

    override fun onCreate() {
        super.onCreate()
        app = this
        settings = SettingsRepository(this)
        music = MusicRepository(this)
        art = ArtRepository(this)
        charts = ChartRepository(this)
        scores = ScoreRepository(this)
        preview = PreviewPlayer(this)
        songColors = SongColorRepository(this)
        albums = AlbumRepository(this)
    }

    /** The colors [song] uses in the game (the player's choice or the cover's). */
    suspend fun colorsFor(song: Song): ResolvedColors =
        ResolvedColors.resolve(songColors.get(song.key), settings.current.colorFlow, art.palette(song), art.colors(song))

    companion object {
        lateinit var app: GatoApp
            private set
    }
}
