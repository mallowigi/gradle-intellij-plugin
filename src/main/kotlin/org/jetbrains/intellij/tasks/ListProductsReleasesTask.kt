package org.jetbrains.intellij.tasks

import org.apache.tools.ant.util.TeeOutputStream
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.ConventionTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.setProperty
import org.jetbrains.intellij.Version
import org.jetbrains.intellij.Version.Companion.parse
import org.jetbrains.intellij.logCategory
import org.jetbrains.intellij.model.ProductsReleases
import org.jetbrains.intellij.model.XmlExtractor
import javax.inject.Inject

@Suppress("UnstableApiUsage")
open class ListProductsReleasesTask @Inject constructor(
    objectFactory: ObjectFactory,
) : ConventionTask() {

    @Input
    @Optional
    val updatePaths = objectFactory.listProperty<String>()

    @OutputFile
    val outputFile: RegularFileProperty = objectFactory.fileProperty()

    @Input
    @Optional
    val types = objectFactory.listProperty<String>()

    @Input
    @Optional
    val sinceVersion = objectFactory.property<String>()

    @Input
    @Optional
    val untilVersion = objectFactory.property<String>()

    @Input
    @Optional
    val releaseChannels = objectFactory.setProperty<Channel>()

    private val context = logCategory()

    @TaskAction
    fun list() {
        val extractor = XmlExtractor<ProductsReleases>(context)
        val releases = updatePaths.get().mapNotNull(extractor::fetch)

        val since = sinceVersion.get().run(::parse)
        val until = untilVersion.orNull?.run(::parse)
        val channels = releaseChannels.get()

        val result = releases
            .map(ProductsReleases::products)
            .flatten()
            .asSequence()
            .flatMap { product -> product.codes.map { it to product }.asSequence() }
            .filter { (type) -> types.get().contains(type) }
            .flatMap { (type, product) -> product.channels.map { type to it }.asSequence() }
            .filter { (_, channel) -> channels.contains(Channel.valueOf(channel.status.toUpperCase())) }
            .flatMap { (type, channel) -> channel.builds.map { type to it.version.run(::parse) }.asSequence() }
            .filter { (_, version) -> version >= since && (until == null || version <= until) }
            .groupBy { (type, version) -> "$type-${version.major}.${version.minor}" }
            .mapNotNull {
                it.value.maxByOrNull { (_, version) -> version.patch }?.let { (type, version) -> "$type-${version.asRelease()}" }
            }
            .distinct()
            .toList()

        outputFile.get().asFile.outputStream().use { os ->
            result.joinToString("\n").apply {
                TeeOutputStream(System.out, os).write(toByteArray())
            }
        }
    }

    private fun Version.asRelease() = "$major.$minor" + (".$patch".takeIf { patch > 0 } ?: "")

    enum class Channel {
        EAP, MILESTONE, BETA, RELEASE,
    }
}
