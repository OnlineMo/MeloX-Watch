package io.github.onlinemo.meloxwatch.data.auth

/**
 * 本地会话密文边界，实现负责把存储和加密工作调度到 IO，不能隐式请求网络。
 *
 * [load] 不验证账号；[seal] 只为已验证会话生成密文，不执行保存。调用方决定登录接受点，
 * 接受后以 NonCancellable 调用 [write]，按实际结果更新状态。写入失败可能留下未知磁盘结果，
 * 不能推定旧内容已回滚。[clear] 只清除此会话拥有的文件，不触及本地曲库或原始音频。
 */
internal interface SessionStore {
    suspend fun load(): StoredSession?
    suspend fun seal(session: VerifiedSession): SealedSession
    suspend fun write(session: SealedSession)
    suspend fun clear()
}

/**
 * 单个 store 拥有的密文缓冲；生成它的 owner 才能通过 [bytes] 取得独立副本。
 *
 * 接收者负责清零返回的副本，保存调用方负责在使用后 [close]。关闭可重复调用，会清零
 * 内部缓冲并拒绝后续读取；读取与关闭同步，不能把此 token 交给另一 store 保存。
 */
internal class SealedSession(private val owner: Any, private val encrypted: ByteArray) : AutoCloseable {
    private var closed = false
    @Synchronized fun bytes(requester: Any): ByteArray {
        if (closed || requester !== owner) throw AuthException(AuthIssue.STORAGE_UNAVAILABLE)
        return encrypted.copyOf()
    }
    @Synchronized override fun close() {
        closed = true
        encrypted.fill(0)
    }
    override fun toString(): String = "SealedSession(redacted)"
}
