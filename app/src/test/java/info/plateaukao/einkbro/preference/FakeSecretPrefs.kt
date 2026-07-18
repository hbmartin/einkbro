package info.plateaukao.einkbro.preference

class FakeSecretPrefs(
    val values: MutableMap<String, String> = mutableMapOf(),
) : SecretPrefs {
    override fun getString(key: String, defaultValue: String): String =
        values[key] ?: defaultValue

    override fun putString(key: String, value: String) {
        putAll(mapOf(key to value))
    }

    override fun putAll(values: Map<String, String>) {
        values.forEach { (key, value) ->
            if (value.isEmpty()) this.values.remove(key) else this.values[key] = value
        }
    }

    override fun snapshot(keys: Collection<String>): Map<String, String> =
        values.filterKeys(keys::contains)

    override fun ensureReady() = Unit
}
