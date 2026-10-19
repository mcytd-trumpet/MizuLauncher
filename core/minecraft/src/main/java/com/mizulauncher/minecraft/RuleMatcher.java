package com.mizulauncher.minecraft;

import com.mizulauncher.minecraft.model.Rule;

import java.util.List;
import java.util.Map;

/**
 * 平台规则判定（Mojang {@code rules} 语义）——全启动器唯一实现。
 *
 * <p>语义（与 Mojang 官方 {@code Rule.Entry#applies} 及 PCL2 一致）：</p>
 * <ul>
 *   <li>rules 为 {@code null} 或空 → 放行；</li>
 *   <li>按顺序取<b>第一条命中</b>的规则，由它的 {@code action} 决定放行/拒绝；</li>
 *   <li>没有任何规则命中 → <b>拒绝</b>（官方默认 disallow）。</li>
 * </ul>
 *
 * <p>第三条最容易被写错：直觉上「没规则拦我 = 随便用」，但 Mojang 的原意是
 * 「rules 一旦写上，就是白名单」。例如 {@code ca.weblite:java-objc-bridge}
 * 只有一条 {@code allow + os=osx}，在 Android 上必须被排除，而不是「没命中所以放行」。
 * 依赖库与 JVM 参数都走这条规则，写错会直接把 macOS 专用库塞进 Android 的 classpath。</p>
 */
public final class RuleMatcher {

    private RuleMatcher() {
    }

    /** 一组 rules 在该平台是否放行 */
    public static boolean allows(List<Rule> rules, PlatformContext ctx) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        int size = rules.size();
        for (int i = 0; i < size; i++) {
            Rule rule = rules.get(i);
            if (ruleHits(rule, ctx)) {
                return allowOf(rule.getAction());
            }
        }
        // 写了 rules 却一条都没命中 → 默认拒绝
        return false;
    }

    /** 单条规则是否命中（os 段 + features 段全部满足） */
    public static boolean ruleHits(Rule rule, PlatformContext ctx) {
        if (rule == null) {
            return false;
        }
        if (!osHits(rule.getOs(), ctx)) {
            return false;
        }
        Map<String, Boolean> want = rule.getFeatures();
        if (want != null) {
            for (Map.Entry<String, Boolean> entry : want.entrySet()) {
                boolean expected = Boolean.TRUE.equals(entry.getValue());
                boolean actual = ctx.getFeatures() != null
                        && Boolean.TRUE.equals(ctx.getFeatures().get(entry.getKey()));
                if (expected != actual) {
                    return false;
                }
            }
        }
        return true;
    }

    /** os 段：name / arch / version 三条件都满足（缺省视为不限） */
    private static boolean osHits(Rule.OsRule os, PlatformContext ctx) {
        if (os == null) {
            return true;
        }
        String name = os.getName();
        if (name != null && !name.isEmpty()) {
            // Android 运行时等价于 linux：显式声明 android 的规则直接命中，
            // 否则与实际 osName 比较（PlatformContext 默认 "linux"）。
            boolean isAndroid = name.equalsIgnoreCase("android");
            if (!isAndroid && !name.equalsIgnoreCase(ctx.getOsName())) {
                return false;
            }
        }
        String arch = os.getArch();
        if (arch != null && !arch.isEmpty() && !archHits(arch, ctx.getOsArch())) {
            return false;
        }
        String version = os.getVersion();
        if (version != null && !version.isEmpty()) {
            String actual = ctx.getOsVersion() == null ? "" : ctx.getOsVersion();
            if (!actual.matches(".*" + version + ".*")) {
                return false;
            }
        }
        return true;
    }

    /** action 为 null 时按官方默认值 allow 处理（Gson 反序列化不会填 Kotlin 默认值） */
    public static boolean allowOf(String action) {
        if (action == null) {
            return true;
        }
        return action.equalsIgnoreCase("allow");
    }

    private static boolean archHits(String ruleArch, String actual) {
        String want = ruleArch.toLowerCase();
        String have = actual == null ? "" : actual.toLowerCase();
        if ("x86".equals(want)) {
            return have.startsWith("x86") && !have.contains("64");
        }
        if ("x86_64".equals(want) || "amd64".equals(want)) {
            return have.contains("64") && (have.contains("x86") || have.contains("amd64"));
        }
        if ("arm64".equals(want) || "aarch64".equals(want)) {
            return have.contains("aarch64") || have.contains("arm64");
        }
        if ("arm".equals(want) || "arm32".equals(want)) {
            return have.contains("arm") && !have.contains("64");
        }
        // 未知架构名不参与筛选
        return true;
    }
}
