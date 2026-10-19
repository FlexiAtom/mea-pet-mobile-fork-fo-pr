package com.meapet.mobile.backup

import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.chat.ConversationManager
import com.meapet.mobile.chat.ConversationStore
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryRepository
import com.meapet.mobile.memory.MemorySerialization
import com.meapet.mobile.memory.MemoryType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 导入落地用到的存储侧原语（步骤⑥）：解析门槛、整体替换、撤销原样装回。
 * 这三件事决定"坏包会不会毁掉在用数据"，必须在接 UI 之前钉死。
 */
class StoreImportApisTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun oneMessageSnapshot(content: String, id: String) =
        """[{"role":"user","content":"$content","id":"$id","timestamp":1}]"""

    @Test
    fun conversation_replacesInsteadOfPrepending() = runTest {
        val store = ConversationStore(tmp.newFolder(), backgroundScope)
        val mgr = ConversationManager(store = store)
        mgr.addMessage(ChatMessage(ChatRole.assistant, "现存的一条"))

        mgr.replaceAll(store.parseSnapshot(oneMessageSnapshot("导入的消息", "i1"))!!)

        assertEquals(listOf("导入的消息"), mgr.getMessages().map { it.content })
    }

    @Test
    fun conversation_undoPathRestoresExactlyWhatWasThere() = runTest {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir, backgroundScope)
        val mgr = ConversationManager(store = store)
        val original = listOf(
            ChatMessage(ChatRole.user, "原名一字"),
            ChatMessage(ChatRole.assistant, "原名二字"),
        )
        original.forEach { mgr.addMessage(it) }

        val before = mgr.getMessages()
        mgr.replaceAll(store.parseSnapshot(oneMessageSnapshot("外部的一条", "x1"))!!)
        assertEquals(1, mgr.size)

        // AppBackupApplier 的撤销就是"把 before 再 replaceAll 一次"
        mgr.replaceAll(before)
        assertEquals(original.map { it.content }, mgr.getMessages().map { it.content })
    }

    @Test
    fun parseSnapshot_rejectsUnparsableWithoutTouchingAnything() = runTest {
        val store = ConversationStore(tmp.newFolder(), backgroundScope)
        val mgr = ConversationManager(store = store)
        mgr.addMessage(ChatMessage(ChatRole.user, "别动我"))

        listOf("", "not json", "{不是数组}", """[{"role":"WRONG","content":"x"}]""").forEach { bad ->
            assertNull("不该被放行: [$bad]", store.parseSnapshot(bad))
        }
        assertEquals("别动我", mgr.getMessages().single().content)
    }

    @Test
    fun memory_replaceWith_returnsPrevious_andRestoreAllPutsItBack() = runTest {
        val repo = MemoryRepository(tmp.newFolder(), maxItems = 10)
        repo.save(MemoryItem(id = "m1", content = "在用的一条", type = MemoryType.FACTUAL))

        val incoming = MemorySerialization.encode(listOf(MemoryItem(id = "m2", content = "包里的一条")))
        val previous = repo.replaceWith(incoming)

        assertNotNull(previous)
        assertEquals(listOf("m1"), previous!!.map { it.id })
        assertEquals(listOf("m2"), repo.getAll().map { it.id })

        repo.restoreAll(previous)
        assertEquals(listOf("m1"), repo.getAll().map { it.id })
    }

    @Test
    fun memory_unparsablePayload_leavesStoreUntouched() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.save(MemoryItem(id = "m1", content = "在用的一条"))

        assertNull(repo.replaceWith("""[{"nope":1}]"""))
        assertNull(repo.replaceWith("""["不是记忆条目"]"""))
        assertEquals(listOf("m1"), repo.getAll().map { it.id })
    }

    @Test
    fun memory_replaceWith_loadsDiskFirstSoUndoIsNotAnEmptyList() = runTest {
        // 内存还没加载过时直接换，"原内容"会被错报成空表——回滚等于清空
        val dir = tmp.newFolder()
        MemoryRepository(dir).save(MemoryItem(id = "m1", content = "磁盘上已有的一条"))

        val fresh = MemoryRepository(dir)
        val previous = fresh.replaceWith(MemorySerialization.encode(emptyList<MemoryItem>()))

        assertNotNull(previous)
        assertTrue("撤销用的原内容必须来自磁盘", previous!!.isNotEmpty())
        assertEquals("磁盘上已有的一条", previous.single().content)
    }

    @Test
    fun memory_diskContentSurvivesReplaceThenRestore() = runTest {
        val dir = tmp.newFolder()
        val repo = MemoryRepository(dir)
        repo.save(MemoryItem(id = "m1", content = "原始", type = MemoryType.FACTUAL))

        val previous = repo.replaceWith(
            MemorySerialization.encode(listOf(MemoryItem(id = "m2", content = "外来")))
        )!!
        repo.restoreAll(previous)

        // 换一个冷实例读盘：撤销真的落到磁盘，不只是内存
        assertEquals(listOf("m1"), MemoryRepository(dir).getAll().map { it.id })
    }
}
