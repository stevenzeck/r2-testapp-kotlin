package org.readium.r2.testapp.catalogs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.readium.r2.lcp.LcpService
import org.readium.r2.shared.extensions.getPublicationOrNull
import org.readium.r2.shared.extensions.mediaType
import org.readium.r2.shared.extensions.tryOrNull
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.asset.FileAsset
import org.readium.r2.shared.publication.opds.images
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.flatMap
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.streamer.Streamer
import org.readium.r2.testapp.R
import org.readium.r2.testapp.R2App
import org.readium.r2.testapp.bookshelf.BookRepository
import org.readium.r2.testapp.db.BookDatabase
import org.readium.r2.testapp.opds.OPDSDownloader
import org.readium.r2.testapp.opds.OpdsDownloadResult
import org.readium.r2.testapp.utils.extensions.copyToTempFile
import org.readium.r2.testapp.utils.extensions.moveTo
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

const val DOWNLOAD_URI = "publication_download_uri"

class PublicationDownloadWorker(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private lateinit var mOpdsDownloader: OPDSDownloader
    private lateinit var mLcpService: Try<LcpService, Exception>
    private lateinit var mStreamer: Streamer
    private lateinit var repository: BookRepository

    override suspend fun doWork(): Result {

        mOpdsDownloader = OPDSDownloader(context)
        mLcpService = LcpService(context)
            ?.let { Try.success(it) }
            ?: Try.failure(Exception("liblcp is missing on the classpath"))
        mStreamer = Streamer(
            context,
            contentProtections = listOfNotNull(
                mLcpService.getOrNull()?.contentProtection()
            )
        )
        val booksDao = BookDatabase.getDatabase(context).booksDao()
        repository = BookRepository(booksDao)

        val uri = inputData.getString(DOWNLOAD_URI)
        val publication = inputData.getPublicationOrNull()

        if (uri == null) {
            return if (publication != null) {
                val downloadUrl = getDownloadURL(publication)
                val publicationUrl = mOpdsDownloader.publicationUrl(downloadUrl.toString())
                when (publicationUrl) {
                    is OpdsDownloadResult.OnSuccess -> {
                        val id =
                            addPublicationToDatabase(publicationUrl.data.first, "epub", publication)
                        if (id != -1L) {
                            Result.success()
                        } else {
                            Result.failure()
                        }
                    }
                    is OpdsDownloadResult.OnFailure -> Result.failure()
                }
            } else {
                Result.failure()
            }
        } else {
            Uri.parse(uri).copyToTempFile(context, R2App.R2DIRECTORY)
                ?.let { sourceFile ->
                    val sourceMediaType = sourceFile.mediaType()
                    val publicationAsset: FileAsset =
                        if (sourceMediaType != MediaType.LCP_LICENSE_DOCUMENT)
                            FileAsset(sourceFile, sourceMediaType)
                        else {
                            mLcpService
                                .flatMap { it.acquirePublication(sourceFile) }
                                .fold(
                                    {
                                        val mediaType =
                                            MediaType.of(fileExtension = File(it.suggestedFilename).extension)
                                        FileAsset(it.localFile, mediaType)
                                    },
                                    {
                                        tryOrNull { sourceFile.delete() }
                                        Timber.d(it)
                                        val data =
                                            Data.Builder().putString("error", it.message).build()
                                        return Result.failure(data)
                                    }
                                )
                        }

                    val mediaType = publicationAsset.mediaType()
                    val fileName = "${UUID.randomUUID()}.${mediaType.fileExtension}"
                    val libraryAsset = FileAsset(File(R2App.R2DIRECTORY + fileName), mediaType)

                    try {
                        publicationAsset.file.moveTo(libraryAsset.file)
                    } catch (e: Exception) {
                        Timber.d(e)
                        tryOrNull { publicationAsset.file.delete() }
                        val data = Data.Builder()
                            .putString("error", context.getString(R.string.unable_to_move_pub))
                            .build()
                        return Result.failure(data)
                    }

                    val extension = libraryAsset.let {
                        it.mediaType().fileExtension ?: it.file.extension
                    }

                    val isRwpm = libraryAsset.mediaType().isRwpm

                    // FIXME
                    val sourceUrl: String? = null
                    val bddHref =
                        if (!isRwpm)
                            libraryAsset.file.path
                        else
                            sourceUrl ?: run {
                                Timber.e("Trying to add a RWPM to the database from a file without sourceUrl.")
                                return Result.failure()
                            }

                    mStreamer.open(libraryAsset, allowUserInteraction = false, sender = context)
                        .onSuccess {
                            addPublicationToDatabase(bddHref, extension, it).let { id ->

                                if (id != -1L && isRwpm)
                                    tryOrNull { libraryAsset.file.delete() }
                                if (id != -1L) {
                                    return Result.success()
                                } else {
                                    val data = Data.Builder().putString(
                                        "error",
                                        context.getString(R.string.unable_add_pub_database)
                                    ).build()
                                    Result.failure(data)
                                }
                            }
                        }
                        .onFailure {
                            tryOrNull { libraryAsset.file.delete() }
                            Timber.d(it)
                            val data = Data.Builder().putString("error", it.message).build()
                            return Result.failure(data)
                        }
                }
        }
        return Result.success()
    }

    private suspend fun addPublicationToDatabase(
        href: String,
        extension: String,
        publication: Publication
    ): Long {
        val id = repository.insertBook(href, extension, publication)
        storeCoverImage(publication, id.toString())
        return id
    }

    private suspend fun storeCoverImage(publication: Publication, imageName: String) {
        // TODO Figure out where to store these cover images
        val coverImageDir = File("${R2App.R2DIRECTORY}covers/")
        if (!coverImageDir.exists()) {
            coverImageDir.mkdirs()
        }
        val coverImageFile = File("${R2App.R2DIRECTORY}covers/${imageName}.png")

        var bitmap: Bitmap? = null
        if (publication.cover() == null) {
            publication.coverLink?.let { link ->
                bitmap = getBitmapFromURL(link.href)
            } ?: run {
                if (publication.images.isNotEmpty()) {
                    bitmap = getBitmapFromURL(publication.images.first().href)
                }
            }
        } else {
            bitmap = publication.cover()
        }

        val resized = bitmap?.let { Bitmap.createScaledBitmap(it, 120, 200, true) }
        GlobalScope.launch(Dispatchers.IO) {
            val fos = FileOutputStream(coverImageFile)
            resized?.compress(Bitmap.CompressFormat.PNG, 80, fos)
            fos.flush()
            fos.close()
        }
    }

    private fun getBitmapFromURL(src: String): Bitmap? {
        return try {
            val url = URL(src)
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connect()
            val input = connection.inputStream
            BitmapFactory.decodeStream(input)
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    private fun getDownloadURL(publication: Publication): URL? {
        var url: URL? = null
        val links = publication.links
        for (link in links) {
            val href = link.href
            if (href.contains(Publication.EXTENSION.EPUB.value) || href.contains(Publication.EXTENSION.LCPL.value)) {
                url = URL(href)
                break
            }
        }
        return url
    }
}
