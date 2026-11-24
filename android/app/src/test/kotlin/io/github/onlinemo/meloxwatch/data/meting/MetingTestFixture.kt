package io.github.onlinemo.meloxwatch.data.meting

/** 只控制内存存储边界，不使用真实凭据、服务、Keystore 或音乐库。 */
internal class ControlledMetingStore(initial: MetingStoredConfiguration? = null) : MetingConfigurationStore {
    var persistent: MetingStoredConfiguration? = initial
        private set
    var loads = 0
        private set
    var writes = 0
        private set
    var clears = 0
        private set
    var loadFailure: MetingIssue? = null
    var writeFailure: MetingIssue? = null
    var beforeLoad: suspend () -> Unit = {}
    var beforeWrite: suspend () -> Unit = {}
    override suspend fun load(): MetingStoredConfiguration? {
        loads++
        beforeLoad()
        loadFailure?.let { throw MetingException(it) }
        return persistent
    }
    override suspend fun save(configuration: MetingStoredConfiguration) {
        beforeWrite()
        writeFailure?.let { throw MetingException(it) }
        persistent = configuration
        writes++
    }
    override suspend fun clear() { persistent = null; clears++ }
}

internal fun metingConfiguration(
    address: String = "https://service.invalid/api?token=fixture",
    enabled: Boolean = false,
    allowCleartext: Boolean = false,
    mode: MetingEndpointMode = MetingEndpointMode.ENDPOINT,
    generation: Long = 1L,
) = MetingStoredConfiguration(generation, MetingEndpoint.checked(address, mode, allowCleartext),
    enabled, allowCleartext, MetingServiceCredential.checked("Authorization", "Bearer service-fixture"))
