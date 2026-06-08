package com.example.foresightapk

class AppMapper(private val vocab: Map<String, Int>) {
    private val normalizedLabelToVocabLabel: Map<String, String> = vocab.keys
        .groupBy { AppVocab.normalizeLabel(it) }
        .mapValues { (_, labels) -> labels.first() }

    fun map(
        packageName: String,
        appLabel: String,
        overrideLabel: String? = null,
        fallbackToUnknown: Boolean
    ): AppMappingResult {
        val cleanOverride = overrideLabel?.trim().orEmpty()
        if (cleanOverride.isNotEmpty()) {
            resultForLabel(cleanOverride, MappingSource.ManualOverride, 1.0f)?.let { return it }
            ForeSightLog.warn(
                "Mapping override ignored for package=$packageName; " +
                    "vocab label '$cleanOverride' was not found"
            )
        }

        AppPackageAliases.labelsByPackage[packageName]?.let { aliasLabel ->
            resultForLabel(aliasLabel, MappingSource.PackageAlias, 0.95f)?.let { return it }
        }

        resultForLabel(appLabel, MappingSource.ExactLabel, 0.90f)?.let { return it }

        normalizedLabelToVocabLabel[AppVocab.normalizeLabel(appLabel)]?.let { normalizedLabel ->
            resultForLabel(normalizedLabel, MappingSource.NormalizedLabel, 0.75f)?.let { return it }
        }

        resultForLabel(packageName, MappingSource.PackageName, 0.60f)?.let { return it }

        return if (fallbackToUnknown) {
            AppMappingResult(
                modelAppId = UNKNOWN_APP_ID,
                modelLabel = "PAD/unknown",
                source = MappingSource.Unknown,
                confidence = 0.0f
            )
        } else {
            AppMappingResult(
                modelAppId = null,
                modelLabel = null,
                source = MappingSource.Unknown,
                confidence = 0.0f
            )
        }
    }

    private fun resultForLabel(
        label: String,
        source: MappingSource,
        confidence: Float
    ): AppMappingResult? {
        val id = vocab[label] ?: return null
        return AppMappingResult(
            modelAppId = id,
            modelLabel = label,
            source = source,
            confidence = confidence
        )
    }

    companion object {
        const val UNKNOWN_APP_ID = 0
    }
}
