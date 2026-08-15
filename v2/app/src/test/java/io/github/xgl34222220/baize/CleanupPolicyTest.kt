package io.github.xgl34222220.baize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清理档位的安全不变量。
 *
 * 这些断言此前只存在于 shell 的 grep 式 contract 测试里
 * （test-cleanup-policy-contract.sh 检查源码里出现过某个字符串），
 * 那种写法在重构后会误报，也无法覆盖 defaultSelected 这类实际行为。
 */
class CleanupPolicyTest {

    @Test
    fun `没有任何档位启用高风险直删`() {
        CleanupPolicy.entries.forEach { policy ->
            assertEquals(
                "${policy.key} 档位不得开启 deep_high_risk_enabled",
                0,
                policy.values["deep_high_risk_enabled"]
            )
        }
    }

    @Test
    fun `档位不得包含任何调度周期字段`() {
        val forbidden = Regex("^(schedule_|daily_schedule_|autopilot_)")
        CleanupPolicy.entries.forEach { policy ->
            val offenders = policy.values.keys.filter { forbidden.containsMatchIn(it) }
            assertTrue(
                "${policy.key} 档位不应触碰调度字段，但包含：$offenders",
                offenders.isEmpty()
            )
        }
    }

    @Test
    fun `档位不得启用自定义规则`() {
        // 自定义规则由用户自行维护，档位切换不应替用户打开它。
        CleanupPolicy.entries.forEach { policy ->
            assertEquals(
                "${policy.key} 不得自动启用 clean_custom_rules",
                0,
                policy.values["clean_custom_rules"]
            )
        }
    }

    @Test
    fun `保守档只默认勾选低风险`() {
        val conservative = CleanupPolicy.CONSERVATIVE
        assertTrue(conservative.defaultSelected("low"))
        assertFalse(conservative.defaultSelected("medium"))
        assertFalse(conservative.defaultSelected("high"))
        assertFalse(conservative.defaultSelected("critical"))
    }

    @Test
    fun `均衡与积极档默认勾选低风险和中风险，但绝不勾选高风险`() {
        listOf(CleanupPolicy.BALANCED, CleanupPolicy.AGGRESSIVE).forEach { policy ->
            assertTrue(policy.key, policy.defaultSelected("low"))
            assertTrue(policy.key, policy.defaultSelected("medium"))
            assertFalse(policy.key, policy.defaultSelected("high"))
            assertFalse(policy.key, policy.defaultSelected("critical"))
        }
    }

    @Test
    fun `任何档位都不默认勾选高风险或关键风险`() {
        CleanupPolicy.entries.forEach { policy ->
            assertFalse("${policy.key} 不得默认勾选 high", policy.defaultSelected("high"))
            assertFalse("${policy.key} 不得默认勾选 critical", policy.defaultSelected("critical"))
        }
    }

    @Test
    fun `未知风险等级一律不默认勾选`() {
        CleanupPolicy.entries.forEach { policy ->
            assertFalse(policy.defaultSelected(""))
            assertFalse(policy.defaultSelected("unknown"))
            assertFalse(policy.defaultSelected("LOW"))
        }
    }

    @Test
    fun `保守档不允许隔离高风险候选`() {
        assertFalse(CleanupPolicy.CONSERVATIVE.canQuarantineHighRisk)
        assertTrue(CleanupPolicy.BALANCED.canQuarantineHighRisk)
        assertTrue(CleanupPolicy.AGGRESSIVE.canQuarantineHighRisk)
    }

    @Test
    fun `保留天数随档位单调放宽`() {
        // 保守 >= 均衡 >= 积极：越保守保留越久。
        val keys = listOf(
            "app_cache_days", "external_cache_days", "system_logs_days",
            "fragment_days", "installer_temp_days", "apk_package_days",
            "root_shell_days", "quarantine_retention_days"
        )
        keys.forEach { key ->
            val conservative = CleanupPolicy.CONSERVATIVE.values.getValue(key)
            val balanced = CleanupPolicy.BALANCED.values.getValue(key)
            val aggressive = CleanupPolicy.AGGRESSIVE.values.getValue(key)
            assertTrue("$key: 保守($conservative) 应 >= 均衡($balanced)", conservative >= balanced)
            assertTrue("$key: 均衡($balanced) 应 >= 积极($aggressive)", balanced >= aggressive)
        }
    }

    @Test
    fun `id 与 key 解析对未知输入回退到均衡档`() {
        assertEquals(CleanupPolicy.CONSERVATIVE, CleanupPolicy.fromId(0))
        assertEquals(CleanupPolicy.BALANCED, CleanupPolicy.fromId(1))
        assertEquals(CleanupPolicy.AGGRESSIVE, CleanupPolicy.fromId(2))
        assertEquals(CleanupPolicy.BALANCED, CleanupPolicy.fromId(-1))
        assertEquals(CleanupPolicy.BALANCED, CleanupPolicy.fromId(99))

        assertEquals(CleanupPolicy.CONSERVATIVE, CleanupPolicy.fromKey("conservative"))
        assertEquals(CleanupPolicy.BALANCED, CleanupPolicy.fromKey(""))
        assertEquals(CleanupPolicy.BALANCED, CleanupPolicy.fromKey("Conservative"))
    }

    @Test
    fun `id 唯一且与枚举顺序一致`() {
        val ids = CleanupPolicy.entries.map { it.id }
        assertEquals("id 必须唯一", ids.size, ids.toSet().size)
        assertEquals(listOf(0, 1, 2), ids)
    }

    @Test
    fun `key 唯一且为小写下划线形式`() {
        val keys = CleanupPolicy.entries.map { it.key }
        assertEquals("key 必须唯一", keys.size, keys.toSet().size)
        keys.forEach { key ->
            assertTrue("非法 key：$key", Regex("^[a-z][a-z_]*$").matches(key))
        }
    }

    @Test
    fun `每个档位的开关键集合完全一致`() {
        // 缺键会导致切档时残留上一档的值，这类问题很难在界面上发现。
        val reference = CleanupPolicy.BALANCED.values.keys
        CleanupPolicy.entries.forEach { policy ->
            assertEquals(
                "${policy.key} 的配置键集合与均衡档不一致",
                reference,
                policy.values.keys
            )
        }
    }

    @Test
    fun `单文件保护上限为正且随档位放大`() {
        val conservative = CleanupPolicy.CONSERVATIVE.values.getValue("max_file_mb")
        val balanced = CleanupPolicy.BALANCED.values.getValue("max_file_mb")
        val aggressive = CleanupPolicy.AGGRESSIVE.values.getValue("max_file_mb")
        assertTrue(conservative > 0)
        assertTrue(conservative < balanced)
        assertTrue(balanced < aggressive)
    }

    @Test
    fun `autoRisk 只能是 low 或 medium`() {
        CleanupPolicy.entries.forEach { policy ->
            assertTrue(
                "${policy.key} 的 autoRisk=${policy.autoRisk} 越界",
                policy.autoRisk in setOf("low", "medium")
            )
        }
    }

    @Test
    fun `展示文案非空`() {
        CleanupPolicy.entries.forEach { policy ->
            assertTrue(policy.title.isNotBlank())
            assertTrue(policy.subtitle.isNotBlank())
            assertTrue(policy.badge.isNotBlank())
            assertTrue(policy.highlights.isNotEmpty())
            policy.highlights.forEach { assertTrue(it.isNotBlank()) }
        }
    }
}
