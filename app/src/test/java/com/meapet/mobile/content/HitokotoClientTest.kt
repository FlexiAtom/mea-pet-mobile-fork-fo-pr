package com.meapet.mobile.content

import com.meapet.mobile.client.HttpMethod
import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.test.FakeHttpClientEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.test.assertEquals

class HitokotoClientTest {

    private fun response(status: Int, body: String) =
        HttpResponse(status, body.toByteArray(), "application/json")

    @Test
    fun `请求是固定端点的 GET`() = runTest {
        val engine = FakeHttpClientEngine { response(200, """{"hitokoto":"测试"}""") }
        HitokotoClient(engine).fetch()
        val request = engine.requests.single()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals(HitokotoClient.URL, request.url)
    }

    @Test
    fun `2xx 带体解析出句子与出处`() = runTest {
        val engine = FakeHttpClientEngine {
            response(
                200,
                """{"hitokoto":"长安归故里，故里有长安。","type":"诗","from":"归岑春","from_who":"妙达","id":1}"""
            )
        }
        val poem = HitokotoClient(engine).fetch()
        assertEquals("长安归故里，故里有长安。", poem?.text)
        assertEquals("归岑春", poem?.from)
        assertEquals("妙达", poem?.fromWho)
    }

    @Test
    fun `from_who 为 JSON null 时按缺失处理`() = runTest {
        val engine = FakeHttpClientEngine {
            response(200, """{"hitokoto":"明月出天山，苍茫云海间。","from":"关山月","from_who":null}""")
        }
        val poem = HitokotoClient(engine).fetch()
        assertEquals("明月出天山，苍茫云海间。", poem?.text)
        assertEquals("关山月", poem?.from)
        assertNull(poem?.fromWho)
    }

    @Test
    fun `200 但无可用句即失败（状态码不足以判成败）`() = runTest {
        val blank = FakeHttpClientEngine { response(200, """{"hitokoto":""}""") }
        assertNull(HitokotoClient(blank).fetch())
        val noField = FakeHttpClientEngine { response(200, """{"status":"101","msg":"APPKEY为空"}""") }
        assertNull(HitokotoClient(noField).fetch())
    }

    @Test
    fun `非 2xx 或损坏体即失败`() = runTest {
        assertNull(HitokotoClient(FakeHttpClientEngine { response(401, "") }).fetch())
        assertNull(HitokotoClient(FakeHttpClientEngine { response(200, "not-json") }).fetch())
    }
}
