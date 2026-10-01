package org.cyuCBMclean.cyuclear.command.sub

data class HelpLine(
    val key: String,
    val fallback: String,
    val command: String,
    val permission: String?,
)

val MAIN_HELP_LINES = listOf(
    HelpLine("help-bin", "&#D7DEE8› /cyuclear bin &#8A96A8打开虚空垃圾桶", "/cyuclear bin", "cyuclear.use"),
    HelpLine("help-items", "&#D7DEE8› /cyuclear items &#8A96A8手动清理全服掉落物", "/cyuclear items", "cyuclear.admin"),
    HelpLine("help-entities", "&#D7DEE8› /cyuclear entities &#8A96A8手动清理全服游荡实体", "/cyuclear entities", "cyuclear.admin"),
    HelpLine("help-all", "&#D7DEE8› /cyuclear all &#8A96A8手动执行全量大扫除", "/cyuclear all", "cyuclear.admin"),
    HelpLine("help-check", "&#D7DEE8› /cyuclear check &#8A96A8显示准星目标的判定过程", "/cyuclear check", "cyuclear.admin"),
    HelpLine("help-preview", "&#D7DEE8› /cyuclear preview &#8A96A8预演本次清理，不删除实体", "/cyuclear preview", "cyuclear.admin"),
    HelpLine("help-status", "&#D7DEE8› /cyuclear status &#8A96A8查看性能参数、名单规模与 Hook 状态", "/cyuclear status", "cyuclear.admin"),
    HelpLine("help-reload", "&#D7DEE8› /cyuclear reload &#8A96A8重载配置并应用总开关", "/cyuclear reload", "cyuclear.admin"),
    HelpLine("help-lang", "&#D7DEE8› /cyuclear lang <zh_cn|en_us> &#8A96A8切换插件语言", "/cyuclear lang ", "cyuclear.admin"),
    HelpLine("help-cluster", "&#D7DEE8› /cyuclear cluster &#8A96A8查看跨服连接与严格版本身份", "/cyuclear cluster", "cyuclear.admin"),
    HelpLine("help-menu", "&#D7DEE8› /cyuclear menu &#8A96A8打开管理中心", "/cyuclear menu", "cyuclear.admin"),
    HelpLine("help-runs", "&#D7DEE8› /cyuclear runs [页码] &#8A96A8查看历史清理批次", "/cyuclear runs", "cyuclear.admin"),
    HelpLine("help-run", "&#D7DEE8› /cyuclear run <批次> [details|reasons] &#8A96A8查看批次详情", "/cyuclear run ", "cyuclear.admin"),
    HelpLine("help-recover", "&#D7DEE8› /cyuclear recover <批次> &#8A96A8打开指定批次的恢复物品", "/cyuclear recover ", "cyuclear.admin"),
    HelpLine("help-hotspots", "&#D7DEE8› /cyuclear hotspots [页码] &#8A96A8查看热点区块", "/cyuclear hotspots", "cyuclear.admin"),
    HelpLine("help-here", "&#D7DEE8› /cyuclear here [items|entities|all] &#8A96A8清理当前脚下区块", "/cyuclear here", "cyuclear.admin"),
    HelpLine("help-tp", "&#D7DEE8› /cyuclear tp <世界> <x> [y] <z> &#8A96A8安全传送至指定坐标", "/cyuclear tp ", "cyuclear.admin"),
    HelpLine("help-back", "&#D7DEE8› /cyuclear back &#8A96A8返回上一次传送前的原点", "/cyuclear back", "cyuclear.admin"),
    HelpLine("help-cancel", "&#D7DEE8› /cyuclear cancel &#8A96A8停止当前清理", "/cyuclear cancel", "cyuclear.admin"),
    HelpLine("help-doctor", "&#D7DEE8› /cyuclear doctor &#8A96A8检查配置文件和菜单", "/cyuclear doctor", "cyuclear.admin"),
    HelpLine("help-snapshot", "&#D7DEE8› /cyuclear snapshot &#8A96A8备份当前配置", "/cyuclear snapshot", "cyuclear.admin"),
    HelpLine("help-history", "&#D7DEE8› /cyuclear history [玩家] [页码] &#8A96A8查看虚空桶领取记录", "/cyuclear history", "cyuclear.admin")
)
