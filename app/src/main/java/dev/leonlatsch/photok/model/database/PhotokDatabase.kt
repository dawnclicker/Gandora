/*
 * Copyright 2020–2026 Leon Latsch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.leonlatsch.photok.model.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RenameColumn
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.leonlatsch.photok.sort.data.db.SortDao
import dev.leonlatsch.photok.sort.data.db.model.SortTable
import dev.leonlatsch.photok.model.database.dao.AlbumDao
import dev.leonlatsch.photok.model.database.dao.PhotoDao
import dev.leonlatsch.photok.model.database.entity.AlbumTable
import dev.leonlatsch.photok.model.database.entity.Photo
import dev.leonlatsch.photok.model.database.ref.AlbumPhotoCrossRefTable

// 1. Updated version from 6 to 7
private const val DATABASE_VERSION = 7
const val DATABASE_NAME = "photok.db"

/**
 * Abstract Room Database.
 *
 * @since 1.0.0
 * @author Leon Latsch
 */
@Database(
    entities = [
        Photo::class,
        AlbumTable::class,
        AlbumPhotoCrossRefTable::class,
        SortTable::class,
    ],
    version = DATABASE_VERSION,
    autoMigrations = [
        AutoMigration(
            from = 1,
            to = 2,
            spec = MigrationSpec1To2::class,
        ),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        // 2. Added the step from 5 to 6 (if it was missing) and 6 to 7
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7, spec = Migration6To7::class),
    ]
)
@TypeConverters(Converters::class)
abstract class PhotokDatabase : RoomDatabase() {

    /**
     * Get the data access object for [Photo]
     */
    abstract fun getPhotoDao(): PhotoDao

    abstract fun getAlbumDao(): AlbumDao
    abstract fun getSortDao(): SortDao
}

// 3. New Migration Class for Priority and Custom Covers
class Migration6To7 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        // These ensure the columns exist even if the auto-migration needs a manual nudge
        db.execSQL("ALTER TABLE album ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE album ADD COLUMN custom_thumbnail_uri TEXT")
    }
}

@DeleteColumn.Entries(
    DeleteColumn(
        tableName = "photo",
        columnName = "id"
    ),
)
@RenameColumn.Entries(
    RenameColumn(
        tableName = "photo",
        fromColumnName = "uuid",
        toColumnName = "photo_uuid",
    )
)
class MigrationSpec1To2 : AutoMigrationSpec
