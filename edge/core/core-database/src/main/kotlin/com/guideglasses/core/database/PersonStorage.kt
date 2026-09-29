package com.guideglasses.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import android.util.Log
import com.guideglasses.core.domain.face.FaceEmbedding
import com.guideglasses.core.domain.face.KnownPerson
import com.guideglasses.core.domain.face.PersonRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val relation: String?,
    /** 加密後的特徵向量。**絕不以明文儲存。** */
    val embeddingCipher: ByteArray,
    val embeddingIv: ByteArray,
    /** 特徵維度。換模型時用來判斷舊資料還能不能比對。 */
    val dimension: Int,
    val photoCount: Int,
    val createdAtMillis: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersonEntity) return false
        return id == other.id &&
            name == other.name &&
            relation == other.relation &&
            dimension == other.dimension &&
            photoCount == other.photoCount &&
            createdAtMillis == other.createdAtMillis &&
            embeddingCipher.contentEquals(other.embeddingCipher) &&
            embeddingIv.contentEquals(other.embeddingIv)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (relation?.hashCode() ?: 0)
        result = 31 * result + embeddingCipher.contentHashCode()
        result = 31 * result + embeddingIv.contentHashCode()
        result = 31 * result + dimension
        result = 31 * result + photoCount
        result = 31 * result + createdAtMillis.hashCode()
        return result
    }
}

@Dao
interface PersonDao {

    @Query("SELECT * FROM people ORDER BY createdAtMillis ASC")
    suspend fun all(): List<PersonEntity>

    @Insert
    suspend fun insert(person: PersonEntity): Long

    @Update
    suspend fun update(person: PersonEntity)

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM people")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM people")
    suspend fun count(): Int
}

@Database(entities = [PersonEntity::class], version = 1, exportSchema = false)
abstract class GuideGlassesDatabase : RoomDatabase() {

    abstract fun personDao(): PersonDao

    companion object {
        fun create(context: Context): GuideGlassesDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                GuideGlassesDatabase::class.java,
                "guide-glasses.db",
            ).build()
    }
}

/**
 * 人臉資料的本地儲存。
 *
 * 取代舊後端把照片與特徵放在伺服器檔案系統的做法。三個理由：
 *
 * 1. **隱私** —— 生物特徵不該離開裝置。
 * 2. **離線** —— 走在路上沒網路時，認人這件事不該失效。
 * 3. **延遲** —— 省掉整段網路往返。
 *
 * 特徵向量以 Keystore 金鑰加密後才寫入資料庫，見 [EmbeddingCipher]。
 */
class RoomPersonRepository(
    private val dao: PersonDao,
    private val cipher: EmbeddingCipher = EmbeddingCipher(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : PersonRepository {

    override suspend fun all(): List<KnownPerson> = withContext(ioDispatcher) {
        dao.all().mapNotNull { it.toDomainOrNull() }
    }

    override suspend fun add(
        name: String,
        relation: String?,
        embedding: FaceEmbedding,
    ): KnownPerson = withContext(ioDispatcher) {
        val encrypted = cipher.encrypt(embedding.values)
        val entity = PersonEntity(
            name = name,
            relation = relation?.takeIf { it.isNotBlank() },
            embeddingCipher = encrypted.cipherText,
            embeddingIv = encrypted.iv,
            dimension = embedding.dimension,
            photoCount = 1,
            createdAtMillis = now(),
        )
        val id = dao.insert(entity)
        KnownPerson(
            id = id,
            name = entity.name,
            relation = entity.relation,
            embedding = embedding,
            photoCount = entity.photoCount,
        )
    }

    override suspend fun update(person: KnownPerson) = withContext(ioDispatcher) {
        val existing = dao.all().firstOrNull { it.id == person.id } ?: return@withContext
        val encrypted = cipher.encrypt(person.embedding.values)
        dao.update(
            existing.copy(
                name = person.name,
                relation = person.relation?.takeIf { it.isNotBlank() },
                embeddingCipher = encrypted.cipherText,
                embeddingIv = encrypted.iv,
                dimension = person.embedding.dimension,
                photoCount = person.photoCount,
            ),
        )
    }

    override suspend fun deleteById(id: Long) = withContext(ioDispatcher) {
        dao.deleteById(id)
    }

    /** 一鍵刪除全部。使用者有權隨時撤回同意。 */
    override suspend fun deleteAll() = withContext(ioDispatcher) {
        dao.deleteAll()
    }

    override suspend fun count(): Int = withContext(ioDispatcher) { dao.count() }

    /**
     * 解密失敗時回傳 null 而不是丟例外。
     *
     * 金鑰可能因為使用者清除 App 資料、換裝置還原備份而失效。
     * 這種情況下讓其他還能解開的人正常運作，比整個功能崩掉好。
     */
    private fun PersonEntity.toDomainOrNull(): KnownPerson? {
        val values = cipher.decrypt(
            EmbeddingCipher.Encrypted(embeddingCipher, embeddingIv),
        )
        if (values == null) {
            Log.w(TAG, "無法解密 id=$id 的特徵向量，略過")
            return null
        }
        return KnownPerson(
            id = id,
            name = name,
            relation = relation,
            embedding = FaceEmbedding(values),
            photoCount = photoCount,
        )
    }

    private companion object {
        const val TAG = "PersonRepository"
    }
}

/**
 * 建立人臉資料儲存。
 *
 * 刻意回傳 domain 的 [PersonRepository] 而不是 Room 的 Database ——
 * 這樣 app 模組完全不需要在 classpath 上有 Room，換掉持久化方案時
 * 也不會波及組裝層。
 */
object PersonStorageFactory {

    fun create(context: Context): PersonRepository =
        RoomPersonRepository(GuideGlassesDatabase.create(context).personDao())
}
