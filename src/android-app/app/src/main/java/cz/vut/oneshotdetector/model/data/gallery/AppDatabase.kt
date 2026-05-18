/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.model.data.gallery

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [GalleryImage::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun galleryImageDao(): GalleryImageDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "oneshot_detector.db"
                ).fallbackToDestructiveMigration(true).build().also { instance = it }
            }
        }
    }
}
