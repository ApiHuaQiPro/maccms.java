# APPK-2 附表 E：模板包清单（MacCMS 官方 v2020.1000.1035 的 default_pc + default_wap）

> 属于 `docs/prd/APPK-2.md`（v7；本附表内容自 v6 起未改动）。冲突时以主文件为准。本附表固定 FR-1、AC-1、AC-20、附表 B 的模板包范围。清单由 `git ls-tree -r -l` 对官方提交导出，未做任何改动；第 3 列是 git blob SHA-1，验收时用 `git hash-object <文件>` 逐个比对。

## E.1 来源

| 项 | 值 |
| --- | --- |
| 官方仓库 | `https://github.com/magicblack/maccms10` |
| 标签 | `v2020.1000.1035`（提交日期 2020-08-17 UTC） |
| 提交号 | `4c418c2703692148a937ad92893ab8a494bff742` |
| `template/` 目录树 SHA-1 | `9398a1ed539cc754b7513c76e12134b3b5b49a53` |
| `template/default_pc/` 目录树 SHA-1 | `4c8d4ce6f8a68c343f94ccb15beda9157d77aea7` |
| `template/default_wap/` 目录树 SHA-1 | `a211f900e060c1d405f2c9d75523e02abac45b80` |
| 取得方式 | GitHub API 与浅克隆（`git clone --depth 1 --branch v2020.1000.1035`）读取，两种方式文件数一致（274） |
| 为什么选它 | 官方仓库里最后一个仍带完整默认模板的版本；`v2021.1000.1081`（标签提交 `6f7e4895406a8fdc216f338527c83d207a1ab45f`）的 `template/` 下只有 1 个文件，此后各版同样不再带模板；仓库 `php/` 的 `2023.1000.3051` 对应标签（提交 `82fd9e26c2ba566b4efe46549b27d5f3023167ca`）`template/default/` 只有 3 个文件，没有 `{maccms:…}` 模板、手机模板和 `ads/` |
| 与 PHP 对照版本的关系 | **版本不同**：模板是 `2020.1000.1035`，PHP 对照代码是仓库 `php/application/extra/version.php` 的 `2023.1000.3051`。决定来源见主文件 OP-1 |

## E.2 目录与文件数

| 目录 | 文件数 | 说明 |
| --- | --- | --- |
| `template/default_pc/`（合计） | 147 | 全部文件 |
| `template/default_pc/`（根，不含子目录） | 1 | `info.ini` |
| `template/default_pc/html` | 92 | 页面模板 `.html` |
| `template/default_pc/ads` | 1 | 广告脚本 |
| `template/default_pc/css` | 4 | 样式 |
| `template/default_pc/js` | 11 | 脚本 |
| `template/default_pc/images` | 38 | 图片 |
| `template/default_wap/`（合计） | 127 | 全部文件 |
| `template/default_wap/`（根，不含子目录） | 1 | `info.ini` |
| `template/default_wap/html` | 76 | 页面模板 `.html` |
| `template/default_wap/ads` | 0 | 该目录在官方包中不存在 |
| `template/default_wap/css` | 2 | 样式 |
| `template/default_wap/js` | 9 | 脚本 |
| `template/default_wap/images` | 39 | 图片 |
| 合计 | 274 | `default_pc` 147 + `default_wap` 127；`template/` 下没有其他目录或文件 |

## E.3 文件清单

| 序号 | 路径 | 字节数 | git blob SHA-1 |
| --- | --- | --- | --- |
| 1 | `template/default_pc/ads/aaa.js` | 18 | `3602ccdb3cea7dd0dd9c75f185aeff490af98344` |
| 2 | `template/default_pc/css/actor.css` | 11843 | `fe87da9e8c2ba48bbcf573856c9fcffbcf281dae` |
| 3 | `template/default_pc/css/member.css` | 8627 | `8ca94c32fa55b969973ef2cf0b1ba688a5fc7ced` |
| 4 | `template/default_pc/css/style.css` | 114510 | `f897da0418368661bdb4d0a02b31fc8015397d72` |
| 5 | `template/default_pc/css/user.css` | 28948 | `45ba436eca2264b539dd642f01af72677e2e26df` |
| 6 | `template/default_pc/html/actor/detail.html` | 9208 | `029b55fa5ed884c381a2e8a571c91882fd0fec04` |
| 7 | `template/default_pc/html/actor/index.html` | 3539 | `bafc9b24b04ae2e6b07d3d3cdd777a58243c6c5c` |
| 8 | `template/default_pc/html/actor/rss.html` | 410 | `c661c5dfc8ba851360f5ee530a50dac83b38c09a` |
| 9 | `template/default_pc/html/actor/show.html` | 4877 | `8f5605f314c7204f9c426038695dd8d207b781a4` |
| 10 | `template/default_pc/html/art/confirm.html` | 1406 | `8b4974427ad6795b8ef995561d98a91f26ec41ae` |
| 11 | `template/default_pc/html/art/detail.html` | 3365 | `5dc275d1d7372daf22609cefb7082d6814189c76` |
| 12 | `template/default_pc/html/art/detail_pwd.html` | 2016 | `bfc0e49af09b3f1353986a820d72feded948a142` |
| 13 | `template/default_pc/html/art/index.html` | 1756 | `19c661a67cda6874690a30d4228eed488ee170e8` |
| 14 | `template/default_pc/html/art/rss.html` | 994 | `8c7b91d56ba400e0eb7ec85cebe792c633157537` |
| 15 | `template/default_pc/html/art/search.html` | 379 | `fedcfe408e862e20d545285c027793b8d7c4547f` |
| 16 | `template/default_pc/html/art/type.html` | 1593 | `9c3286996abd2f66a95235d6957dc3104b5a0206` |
| 17 | `template/default_pc/html/comment/ajax.html` | 6496 | `88bf7df9aff6d9561a304a9163d37f42cd0118f2` |
| 18 | `template/default_pc/html/comment/index.html` | 3769 | `41a3b5f8d0156135c398713cd69cb16cea96b925` |
| 19 | `template/default_pc/html/gbook/index.html` | 2824 | `c210a9ccd20e31fdd0cb180b2b1c4599d4ab03a8` |
| 20 | `template/default_pc/html/gbook/report.html` | 1096 | `34c369f84d667cc230800241a99bc912ba8e3776` |
| 21 | `template/default_pc/html/index/index.html` | 14610 | `b41705edf30644c0b4e13cf43400d7856fd38b06` |
| 22 | `template/default_pc/html/label/rank.html` | 6361 | `26923d204f4edd62faea48113063a434c2805078` |
| 23 | `template/default_pc/html/map/index.html` | 2371 | `65df461f659c0d02070ea286548b9099e20c6b8a` |
| 24 | `template/default_pc/html/plot/detail.html` | 2529 | `43bc80e8832f1866b72b08662b9dcbd3e68cafb1` |
| 25 | `template/default_pc/html/plot/index.html` | 176 | `3817ccc03e4762317b7d23d09dff714d895586fd` |
| 26 | `template/default_pc/html/public/browser.html` | 4394 | `ba6b2755555f6ba83026f4520c076ce36214a437` |
| 27 | `template/default_pc/html/public/close.html` | 571 | `2b872167910ef9d70bcfa0690dfe5e63184c035b` |
| 28 | `template/default_pc/html/public/digg.html` | 672 | `3436e9862abf7bd661f1692f5fe71b360f38eb15` |
| 29 | `template/default_pc/html/public/foot.html` | 890 | `f255ce154c5ede4536c50bd30ac0265d0eba8589` |
| 30 | `template/default_pc/html/public/head.html` | 2431 | `dfa34a69b28d752ff8bf0f97f2018ff5db3e13df` |
| 31 | `template/default_pc/html/public/include.html` | 828 | `0a4ea65d45a5abc26712afecf9153746218a4401` |
| 32 | `template/default_pc/html/public/jump.html` | 1121 | `26d57d2896822595c17c1f8230b292c2d8d8770f` |
| 33 | `template/default_pc/html/public/msg.html` | 565 | `3bccaab0c85038617a559c892a9be81ade4a303f` |
| 34 | `template/default_pc/html/public/paging.html` | 1475 | `15ade4d930ae364715c25f72782ca71d51fe78f9` |
| 35 | `template/default_pc/html/public/score.html` | 1207 | `e6c29f8d7dac278280ff8bd7c837def37ca6fd4b` |
| 36 | `template/default_pc/html/public/star.html` | 249 | `357b8ec604a95bee8edcfe3367f93af66ee83a6e` |
| 37 | `template/default_pc/html/public/star2.html` | 1197 | `461e88419dcb486d61da4a48c88234a4bca95835` |
| 38 | `template/default_pc/html/role/detail.html` | 308 | `98945fb658fcb7e08e6cc14573b8a8ea3a5aefe3` |
| 39 | `template/default_pc/html/role/index.html` | 123 | `566549bdf8fae810809c1a81066000687cb338f6` |
| 40 | `template/default_pc/html/role/show.html` | 123 | `566549bdf8fae810809c1a81066000687cb338f6` |
| 41 | `template/default_pc/html/rss/baidu.html` | 313 | `c8354676f76f2e6f78ec3f4f0f7ba0cd8b84e688` |
| 42 | `template/default_pc/html/rss/bing.html` | 313 | `c8354676f76f2e6f78ec3f4f0f7ba0cd8b84e688` |
| 43 | `template/default_pc/html/rss/google.html` | 571 | `e19a0322baebd5183811f1952177b38c04b1f43f` |
| 44 | `template/default_pc/html/rss/index.html` | 833 | `7f631c2450dc5ddad073da3f620e189e2a30a8bf` |
| 45 | `template/default_pc/html/rss/sm.html` | 313 | `c8354676f76f2e6f78ec3f4f0f7ba0cd8b84e688` |
| 46 | `template/default_pc/html/rss/so.html` | 276 | `62c7852ffad5ad14fbf402820cef6cc998cbffbf` |
| 47 | `template/default_pc/html/rss/sogou.html` | 313 | `c8354676f76f2e6f78ec3f4f0f7ba0cd8b84e688` |
| 48 | `template/default_pc/html/topic/detail.html` | 2823 | `b00803393900991a347ba6665689e089b2422715` |
| 49 | `template/default_pc/html/topic/index.html` | 1556 | `2a8086ebf50a6d323fc0304bf22b1f517ffedf19` |
| 50 | `template/default_pc/html/user/ajax_info.html` | 829 | `40b34786ab341d1846a437f86620e9ae1f133f61` |
| 51 | `template/default_pc/html/user/ajax_login.html` | 1088 | `62f42fe2dc57aa9160359fe6ab15dea27ce432de` |
| 52 | `template/default_pc/html/user/bind.html` | 5109 | `1c20eff1376d25beba74933db5b7549a985d6d5d` |
| 53 | `template/default_pc/html/user/buy.html` | 4033 | `ddb9cb11bf60a511256aa73d26fb4227fc35f1be` |
| 54 | `template/default_pc/html/user/cards.html` | 4178 | `aeccd616de38f3eaa1e0b47731fdfb9d94503a48` |
| 55 | `template/default_pc/html/user/cash.html` | 7799 | `c3c3b5ef566374044b1569ea31bf4f743a7afa2c` |
| 56 | `template/default_pc/html/user/downs.html` | 4815 | `7bc33ea19aac7fddc803c6f203bfbbf7b47943aa` |
| 57 | `template/default_pc/html/user/favs.html` | 4803 | `25d83dc315eb8351abe53ace051a8781f06c988c` |
| 58 | `template/default_pc/html/user/findpass.html` | 4024 | `4d9057cb3ef45a21696aae621cefa8ad2097881e` |
| 59 | `template/default_pc/html/user/findpass_msg.html` | 4979 | `4e796e97ff5d96ae68710b56214cd9a6c4c2511b` |
| 60 | `template/default_pc/html/user/foot.html` | 1136 | `91da6f66817ac86d835ebb3759c42a7dbe426b66` |
| 61 | `template/default_pc/html/user/head.html` | 518 | `d0f7baa13c2e4d09b16fe443196c1b5640e452fb` |
| 62 | `template/default_pc/html/user/include.html` | 962 | `3d4d8d4e0c08c35e0cd330e5aa122695cd057d5c` |
| 63 | `template/default_pc/html/user/index.html` | 6142 | `2bafa3b36d1ef6271f845084c854fba6ea1dfde7` |
| 64 | `template/default_pc/html/user/info.html` | 5224 | `1077088eaf9439872a63c2222269bc62aba6ca7f` |
| 65 | `template/default_pc/html/user/login.html` | 3425 | `b58374fd17dd13120a39854e92a6d2b1d40605e0` |
| 66 | `template/default_pc/html/user/orders.html` | 4508 | `b41d9583475cbf4fff2213a87b1ae3ff54cf0c08` |
| 67 | `template/default_pc/html/user/pay.html` | 3231 | `be73fb0051e163232fbdc85252b7c71fecfedd16` |
| 68 | `template/default_pc/html/user/payment_weixin.html` | 2153 | `46239c4e1e5f3cc1ed9a20b691a735f8e03d3041` |
| 69 | `template/default_pc/html/user/plays.html` | 4924 | `56da25c5ce7659a62170c16343ac28946b1c416f` |
| 70 | `template/default_pc/html/user/plog.html` | 4838 | `3b6d0c974c1316e4776f0965a085bb45a1d65579` |
| 71 | `template/default_pc/html/user/popedom.html` | 2789 | `021e0590b7a7d504c0a2eac6b189de6623047b6b` |
| 72 | `template/default_pc/html/user/reg.html` | 6127 | `fe44c4c8243b3369dc860b3cd42c51bd73ad2698` |
| 73 | `template/default_pc/html/user/reward.html` | 3869 | `4e1a7d714a2e0266d0077e2c08391fa66a6e6dc5` |
| 74 | `template/default_pc/html/user/upgrade.html` | 4284 | `cc4838043e2d009aa4d6f0d8f83a147e8433e8d3` |
| 75 | `template/default_pc/html/vod/confirm.html` | 1490 | `cc246116415d9afa5c79cd33b720d388b76228a1` |
| 76 | `template/default_pc/html/vod/copyright.html` | 1446 | `17936d85a88e1c92f397be6afe58402ca8927294` |
| 77 | `template/default_pc/html/vod/detail.html` | 11121 | `4b0bbc7963b9497aed8e8350122496b66800362b` |
| 78 | `template/default_pc/html/vod/detail_pwd.html` | 2015 | `b6bd217ec2eb69b8b883b2f9046cb0ba4b2fec11` |
| 79 | `template/default_pc/html/vod/down.html` | 2273 | `45645a8b34aea78ee5d3ea145a532be2a4fec71f` |
| 80 | `template/default_pc/html/vod/downer.html` | 2875 | `d4db6e4be1a36b4c0eecbc87818118f8eae5d07b` |
| 81 | `template/default_pc/html/vod/downer_pwd.html` | 2025 | `e15e76bb1fcc690cc4900330fcc98044922863b1` |
| 82 | `template/default_pc/html/vod/index.html` | 19014 | `3b0d0e2df86cbace94644db83e3c58cbafab4e20` |
| 83 | `template/default_pc/html/vod/play.html` | 3889 | `eb5fe17e4a8b4b1809c2ce194ecc0c362bc5d764` |
| 84 | `template/default_pc/html/vod/player.html` | 2888 | `4829573bbfa4bc63450f73159eb674fe28cb15d2` |
| 85 | `template/default_pc/html/vod/player_pwd.html` | 2025 | `8fb61de2b03ba253dc39a4d61a44451dd388b747` |
| 86 | `template/default_pc/html/vod/plot.html` | 2694 | `1e386c374f70c10d971b06294b4f408115f7260e` |
| 87 | `template/default_pc/html/vod/role.html` | 258 | `b0ce0b1d141e1c94ad01245513a2137c8bc56b1a` |
| 88 | `template/default_pc/html/vod/rss.html` | 1106 | `21bc13acbd5c4282b309cfdfc3e195ac509b521e` |
| 89 | `template/default_pc/html/vod/search.html` | 4467 | `3b28b79ab4adf90be524f3e5df275c39852c16d3` |
| 90 | `template/default_pc/html/vod/show.html` | 9818 | `e9d53eb0afbe03ec15c8ca96efbdd42efe4cb57f` |
| 91 | `template/default_pc/html/vod/type.html` | 7704 | `e59580360569adadc32cd625c3532d77ac6c7790` |
| 92 | `template/default_pc/html/website/detail.html` | 7534 | `ec4f689bfb1f4119878e8fba3655c0a024576df1` |
| 93 | `template/default_pc/html/website/index.html` | 2615 | `c30491ba482a8c785ab9ad4c2229cfb689117dfa` |
| 94 | `template/default_pc/html/website/rss.html` | 471 | `7cef8f682fa91261456a45d4c8f1a027b2d63833` |
| 95 | `template/default_pc/html/website/search.html` | 2720 | `a8c326897b0999e388196788b32e778c52500cb6` |
| 96 | `template/default_pc/html/website/show.html` | 8722 | `6adcc2e866be0a511747e1fd4eb52f811d8410d5` |
| 97 | `template/default_pc/html/website/type.html` | 2526 | `ff859c582485b55974eda676507e1dc31e48d8ae` |
| 98 | `template/default_pc/images/404.jpg` | 13089 | `39384b77d8591362e8c4438f3ee266d834109d10` |
| 99 | `template/default_pc/images/a3.gif` | 52 | `f595cca94d04dfc1cb0fbaab11d367048d2d9da1` |
| 100 | `template/default_pc/images/arrow.png` | 2923 | `bc822ea02224357c3a09c2393fdce6eeb70d552d` |
| 101 | `template/default_pc/images/banner_bg.png` | 923 | `7eb96056807deccaf5c1bc13b8aa16e5bc8704a5` |
| 102 | `template/default_pc/images/banner_bg08.png` | 923 | `5ac72e29a5a8a0cbcdbbf3d5799431e6407510ef` |
| 103 | `template/default_pc/images/bread-crumbs.gif` | 169 | `3b5f3043b67c1bb0f399fd5615856695f0b78b81` |
| 104 | `template/default_pc/images/btn.png` | 3206 | `195a82b0fd667621d0229db9e37159f2d0afa55a` |
| 105 | `template/default_pc/images/flt.png` | 6148 | `0de7fe04e8aaceab245559905abcf3a559ebd0f4` |
| 106 | `template/default_pc/images/gb-boot.png` | 4539 | `ca83f8d436d97c1ebef2658fc26dd611ab15ab82` |
| 107 | `template/default_pc/images/gb-reply.png` | 3741 | `c1d4403c38788065909f1fa24aec97c2c7ce0de2` |
| 108 | `template/default_pc/images/gb.png` | 3773 | `095750fc7c03cde74b59946ff91aa13d2fc6f7b1` |
| 109 | `template/default_pc/images/gbboot.png` | 4539 | `ca83f8d436d97c1ebef2658fc26dd611ab15ab82` |
| 110 | `template/default_pc/images/gbreply.png` | 3741 | `c1d4403c38788065909f1fa24aec97c2c7ce0de2` |
| 111 | `template/default_pc/images/ico.png` | 2978 | `4fbce52eec681553416cc615ce696dbe9b45ae5a` |
| 112 | `template/default_pc/images/input.gif` | 65 | `25ef3943b55be72f2dec8c3a610bc7dc34fddcac` |
| 113 | `template/default_pc/images/inputa.gif` | 13132 | `ccb45206bbdb8a07b1ce235ae2232f9c2e3dbcce` |
| 114 | `template/default_pc/images/lazyload.gif` | 1553 | `085ccaecaf5fa5c34bc14cd2c2ed5cbbd8e25dcb` |
| 115 | `template/default_pc/images/logo.jpg` | 14914 | `efa197c84d7f2430fc5bdd8d1c22105529ad4e1c` |
| 116 | `template/default_pc/images/member/cha.png` | 3024 | `ae540d145194d1f00debaf1c2d43f1092527a24a` |
| 117 | `template/default_pc/images/member/cha2.png` | 2230 | `6ca68cb963b574bb5aebb9a5f836cec6e2680985` |
| 118 | `template/default_pc/images/member/cuo.png` | 2354 | `3a7b96fd63c70e95cb8ce86df276bb0a9626c1b1` |
| 119 | `template/default_pc/images/member/dui.png` | 3143 | `09e6d2cee2bdd587c4c197bec2eaadcefb701621` |
| 120 | `template/default_pc/images/member/duigou.png` | 3347 | `07be6ef92533b981bfcfcb6ec7b1dfb0982144b2` |
| 121 | `template/default_pc/images/member/duigou2.png` | 3034 | `d3a10de7bfaee52c581cec68f0823b9430725a6b` |
| 122 | `template/default_pc/images/member/ilogo.gif` | 3081 | `181c93d2ae974bf4ed32971577bd262004562e78` |
| 123 | `template/default_pc/images/member/pers.png` | 718 | `cde510509142a9c7b78739cb59f31f915778e10d` |
| 124 | `template/default_pc/images/member/touxiang.png` | 1066 | `75403f44722ecd473e0086dc4054a46b3521af72` |
| 125 | `template/default_pc/images/news.png` | 2921 | `8803addba86521043c3e9ad65db3588b53c76854` |
| 126 | `template/default_pc/images/num.png` | 3389 | `a8cf713aea4b7c8964c2ee0b759e702c4ac23333` |
| 127 | `template/default_pc/images/rating-total.png` | 3271 | `2fc19051ea3da11fb8dfd1364f600c95e2379a51` |
| 128 | `template/default_pc/images/rating.png` | 4215 | `afd95a3ee3cdd4ab41f413c9f8d27633c101a47f` |
| 129 | `template/default_pc/images/score-bg.gif` | 2089 | `aa636f415690539468173aa49173b29f23dded2b` |
| 130 | `template/default_pc/images/score-scale.gif` | 525 | `1e20e72da2c8ad4c5e3d0f7ffe80e32f9fc61a05` |
| 131 | `template/default_pc/images/search.gif` | 247 | `e2bf4fa3315e6f40ea257ea3df669478f78a9362` |
| 132 | `template/default_pc/images/star-min.gif` | 1975 | `2013e1cc7c784a688e94fcd6b1137f274fcb5432` |
| 133 | `template/default_pc/images/tabx.png` | 919 | `7f101915c2666002f358ccaebe3c95f1948e98ca` |
| 134 | `template/default_pc/images/ui-icon.gif` | 9412 | `82c4a29181c863191ec374ab3d21759c23294b33` |
| 135 | `template/default_pc/images/ui-icony.png` | 2858 | `462358159a62ef45e3e76e7513856ebe96f308d8` |
| 136 | `template/default_pc/info.ini` | 126 | `0bc210695b706192dcb8c7ae0539edea8fcbb34d` |
| 137 | `template/default_pc/js/down.js` | 126998 | `49dad2ced7222ef7a9a3d44816ca8ef4a444c566` |
| 138 | `template/default_pc/js/formValidator-4.0.1.js` | 37757 | `465d390f38dd09232a3a25845be4bfac374cf9e8` |
| 139 | `template/default_pc/js/gold.js` | 3504 | `4ff55e563ff745bc9d80cb1ac22bef786271268e` |
| 140 | `template/default_pc/js/home.js` | 0 | `e69de29bb2d1d6434b8b29ae775ad8c2e48c5391` |
| 141 | `template/default_pc/js/jquery-autocomplete.js` | 14086 | `47eebe8e001a1e1d60644054acbbcff9b8634cc0` |
| 142 | `template/default_pc/js/jquery-lazyload.js` | 2522 | `3d0c54f46aec383e37b4f222d4864a31d14a19e1` |
| 143 | `template/default_pc/js/jquery.base.js` | 6197 | `1639692935ac4977aa320574f53c5dbcb22acc5e` |
| 144 | `template/default_pc/js/jquery.js` | 84985 | `36dad140f20e16e63236e63a2b8a48650021f8f5` |
| 145 | `template/default_pc/js/jquery.lazyload.js` | 1723 | `4c50ab5fc88447f86449a9a97b5b16011dcf4310` |
| 146 | `template/default_pc/js/jquery.superslide.js` | 9432 | `20870add1aaccb036460f54f8e199ae0ed1b2bb1` |
| 147 | `template/default_pc/js/qireobj.js` | 13322 | `b97e77ba494dd6ea003cb064fbe3fe10fb308934` |
| 148 | `template/default_wap/css/style.css` | 116943 | `603239d8ef5aafbea250307fd59361c6bec5de3d` |
| 149 | `template/default_wap/css/user.css` | 53211 | `1fff4bc56477ecbeb66d57c06390071906dbad31` |
| 150 | `template/default_wap/html/art/confirm.html` | 1401 | `22800c5f18caab4980091bf0408fe16c663b3a91` |
| 151 | `template/default_wap/html/art/detail.html` | 1471 | `ce278e49a961c2f1d73ae63573eb2df1d1ea09a8` |
| 152 | `template/default_wap/html/art/detail_pwd.html` | 2015 | `a709469d9dd71dc1ad5ebdee8488ed44a61dd84f` |
| 153 | `template/default_wap/html/art/rss.html` | 907 | `b63adc94a5e235a8d0337f6d7479f4e0416529e8` |
| 154 | `template/default_wap/html/art/search.html` | 379 | `fedcfe408e862e20d545285c027793b8d7c4547f` |
| 155 | `template/default_wap/html/art/type.html` | 1414 | `4115aa80f4bb68e80e4346802e66fe0bbc9808fc` |
| 156 | `template/default_wap/html/comment/ajax.html` | 6432 | `abbc6aec33c2de572b87913833c0276bd93e05a0` |
| 157 | `template/default_wap/html/comment/index.html` | 3769 | `41a3b5f8d0156135c398713cd69cb16cea96b925` |
| 158 | `template/default_wap/html/gbook/index.html` | 3206 | `b8887524cb8d6ab624acaed7f0ef07619b926e63` |
| 159 | `template/default_wap/html/gbook/report.html` | 1096 | `34c369f84d667cc230800241a99bc912ba8e3776` |
| 160 | `template/default_wap/html/index/index.html` | 7058 | `aa473f3694a8a99e3a3f7f7188a484f6e4bf5bc7` |
| 161 | `template/default_wap/html/label/rank.html` | 4539 | `398bb55b0e2f6383f8365bfb7ebcd52f9daaf84a` |
| 162 | `template/default_wap/html/map/index.html` | 1724 | `33609aafbc5df7ff4d243552429c8fc843997a4b` |
| 163 | `template/default_wap/html/plot/detail.html` | 2005 | `1b3d02f3590cb133d8c39e4bde8c48a473ccc697` |
| 164 | `template/default_wap/html/plot/index.html` | 172 | `4d98e3b42161881db4627fac9071c6ff04e1709a` |
| 165 | `template/default_wap/html/public/browser.html` | 4394 | `ba6b2755555f6ba83026f4520c076ce36214a437` |
| 166 | `template/default_wap/html/public/close.html` | 571 | `2b872167910ef9d70bcfa0690dfe5e63184c035b` |
| 167 | `template/default_wap/html/public/digg.html` | 636 | `dd36b38c9dcc9f56bfa1463e6129268cbb3d8a54` |
| 168 | `template/default_wap/html/public/foot.html` | 125 | `4dc900dc2ff887e309076863f738c03b2bab2e97` |
| 169 | `template/default_wap/html/public/head.html` | 1299 | `a26e5bb3d707aabb87709404d5cb2c8275bf084d` |
| 170 | `template/default_wap/html/public/include.html` | 689 | `4ed59ec6fcb453a15c7fe7caf1c73d4ddb4e9326` |
| 171 | `template/default_wap/html/public/jump.html` | 1125 | `32f9811d6772d1611cb923b1a45eb0aa6c11c126` |
| 172 | `template/default_wap/html/public/msg.html` | 565 | `3bccaab0c85038617a559c892a9be81ade4a303f` |
| 173 | `template/default_wap/html/public/paging.html` | 1351 | `1d676acdeb6964a8fd881f2e086758669168ca56` |
| 174 | `template/default_wap/html/public/score.html` | 1207 | `e6c29f8d7dac278280ff8bd7c837def37ca6fd4b` |
| 175 | `template/default_wap/html/public/star.html` | 249 | `357b8ec604a95bee8edcfe3367f93af66ee83a6e` |
| 176 | `template/default_wap/html/public/star2.html` | 1197 | `461e88419dcb486d61da4a48c88234a4bca95835` |
| 177 | `template/default_wap/html/rss/baidu.html` | 313 | `c8354676f76f2e6f78ec3f4f0f7ba0cd8b84e688` |
| 178 | `template/default_wap/html/rss/bing.html` | 313 | `c8354676f76f2e6f78ec3f4f0f7ba0cd8b84e688` |
| 179 | `template/default_wap/html/rss/google.html` | 619 | `073d81fcc8c1c30b9f008c5fd48271ceb95cb913` |
| 180 | `template/default_wap/html/rss/index.html` | 833 | `7f631c2450dc5ddad073da3f620e189e2a30a8bf` |
| 181 | `template/default_wap/html/rss/sm.html` | 313 | `c8354676f76f2e6f78ec3f4f0f7ba0cd8b84e688` |
| 182 | `template/default_wap/html/rss/so.html` | 276 | `62c7852ffad5ad14fbf402820cef6cc998cbffbf` |
| 183 | `template/default_wap/html/rss/sogou.html` | 313 | `c8354676f76f2e6f78ec3f4f0f7ba0cd8b84e688` |
| 184 | `template/default_wap/html/topic/detail.html` | 2801 | `b598df9d136217ccbac6c05837676c778daeeb31` |
| 185 | `template/default_wap/html/topic/index.html` | 1852 | `6a020b468a294607fb56f546500297379a930e12` |
| 186 | `template/default_wap/html/user/ajax_info.html` | 830 | `fccc0728470bcd3f8ca2f33cf02b3bc210e1dce2` |
| 187 | `template/default_wap/html/user/ajax_login.html` | 1088 | `62f42fe2dc57aa9160359fe6ab15dea27ce432de` |
| 188 | `template/default_wap/html/user/bind.html` | 4966 | `90bf6007d14ac7d408bcfe1c37dd6cacfa288f55` |
| 189 | `template/default_wap/html/user/buy.html` | 3360 | `9653927020832b6a6e403c8b13fe95be2a641e82` |
| 190 | `template/default_wap/html/user/cards.html` | 1500 | `68526cb40eab90c47841b53dcd965d90ddfbef6e` |
| 191 | `template/default_wap/html/user/cash.html` | 5031 | `f79404bf8c260d4b36208492ec65a82d9b38ff82` |
| 192 | `template/default_wap/html/user/downs.html` | 2135 | `2b6bb9581eddd3c12dfba617da1c51ebf841a100` |
| 193 | `template/default_wap/html/user/favs.html` | 2404 | `7c2c22f041182c86035686595300ebd85ebc4cba` |
| 194 | `template/default_wap/html/user/findpass.html` | 3445 | `52c170f1aa07874441e56edea39484e554243c99` |
| 195 | `template/default_wap/html/user/findpass_msg.html` | 4202 | `b9f4dddc170c91fb056a781a04d26290e00342eb` |
| 196 | `template/default_wap/html/user/foot.html` | 164 | `043d3c88d32a4fbd73782ff73496c77a77ca2ad8` |
| 197 | `template/default_wap/html/user/head.html` | 0 | `e69de29bb2d1d6434b8b29ae775ad8c2e48c5391` |
| 198 | `template/default_wap/html/user/include.html` | 252 | `d3faa11f7c5679dc7c5937ce255b25c067c9c828` |
| 199 | `template/default_wap/html/user/index.html` | 2946 | `0a313d82b9a9d602f29d171301f35c2390a5f528` |
| 200 | `template/default_wap/html/user/info.html` | 4108 | `545a764c4e9b320345fcfd39cef9be191a55b3ea` |
| 201 | `template/default_wap/html/user/login.html` | 2792 | `cd5f2fcf6abbcea8d6b3053b451d3b9afa94a950` |
| 202 | `template/default_wap/html/user/orders.html` | 1743 | `7e9493477b984329027160245f2b4111ed861f64` |
| 203 | `template/default_wap/html/user/pay.html` | 2990 | `85ba011907238141a199fbcd7187a55d03e5579e` |
| 204 | `template/default_wap/html/user/payment_weixin.html` | 1360 | `47011376a5e217dfd8f661ec44e7dd9b666fad66` |
| 205 | `template/default_wap/html/user/plays.html` | 2099 | `eefd45fc1dbfdf899ca1c22150cb7cb115222b13` |
| 206 | `template/default_wap/html/user/plog.html` | 2011 | `00c19e2c552b5e0e9e381d706d1c6b0e6123bb38` |
| 207 | `template/default_wap/html/user/popedom.html` | 1950 | `2fbb7d86cd9277a7cfc515d23553aa9ec5682586` |
| 208 | `template/default_wap/html/user/reg.html` | 5624 | `b733b48badaca6f9c27fc04bdcf50aa20ae036d2` |
| 209 | `template/default_wap/html/user/reward.html` | 2142 | `b6b7f3f93c2dc6350df1c37912c8a1ebf7b858c1` |
| 210 | `template/default_wap/html/user/upgrade.html` | 3165 | `5ed74aacb7667f2258fad5c6d8ddc27967dfdec9` |
| 211 | `template/default_wap/html/vod/confirm.html` | 1472 | `01d6508848f6f84b998dbcec57a10c4c2fa945fd` |
| 212 | `template/default_wap/html/vod/copyright.html` | 1380 | `74fb07b575aff385adf64395fd156fa4ceceaba8` |
| 213 | `template/default_wap/html/vod/detail.html` | 7835 | `a4dc2a296753263d623f0d6a60e2f64b7f9c4f6e` |
| 214 | `template/default_wap/html/vod/detail_pwd.html` | 2015 | `711e7020efd52f46ab8d606a728391c3d242639f` |
| 215 | `template/default_wap/html/vod/down.html` | 5866 | `4007e7aca2b6a23a31015fee31f9d6b4ed4314f5` |
| 216 | `template/default_wap/html/vod/downer.html` | 2691 | `8e6c0f12145acb6cd7bbd1f834b42b471d82e377` |
| 217 | `template/default_wap/html/vod/downer_pwd.html` | 2066 | `4d8e3b4c25f546d5769967facf1542aa86ee2abc` |
| 218 | `template/default_wap/html/vod/play.html` | 6676 | `8c6bea6679f8d126b390de62151ec93ddaea9ac9` |
| 219 | `template/default_wap/html/vod/player.html` | 2691 | `43723996e75ad69b9b69c0c23adc01a12f69eea8` |
| 220 | `template/default_wap/html/vod/player_pwd.html` | 2067 | `ae797787d9f25e13a0773d7c34d5e18d334a1a80` |
| 221 | `template/default_wap/html/vod/plot.html` | 1778 | `23262e8b6815913cc3b0822f2afac4d308c96dea` |
| 222 | `template/default_wap/html/vod/rss.html` | 1027 | `8f2541f8bf9b3ba3e89fec8221425b0fca015d63` |
| 223 | `template/default_wap/html/vod/search.html` | 3068 | `ad575c9127d14dd4a8639d85bd675857eb7e890f` |
| 224 | `template/default_wap/html/vod/show.html` | 6033 | `5041bf516b63c7be1ae2e9122f6136a03df48146` |
| 225 | `template/default_wap/html/vod/type.html` | 5893 | `f210347523ca9e80f4dc14d87d29c923044ac6a1` |
| 226 | `template/default_wap/images/404.jpg` | 13089 | `39384b77d8591362e8c4438f3ee266d834109d10` |
| 227 | `template/default_wap/images/a3.gif` | 52 | `f595cca94d04dfc1cb0fbaab11d367048d2d9da1` |
| 228 | `template/default_wap/images/arrow.png` | 2923 | `bc822ea02224357c3a09c2393fdce6eeb70d552d` |
| 229 | `template/default_wap/images/banner_bg.png` | 923 | `7eb96056807deccaf5c1bc13b8aa16e5bc8704a5` |
| 230 | `template/default_wap/images/banner_bg08.png` | 923 | `5ac72e29a5a8a0cbcdbbf3d5799431e6407510ef` |
| 231 | `template/default_wap/images/bread-crumbs.gif` | 169 | `3b5f3043b67c1bb0f399fd5615856695f0b78b81` |
| 232 | `template/default_wap/images/btn.png` | 3206 | `195a82b0fd667621d0229db9e37159f2d0afa55a` |
| 233 | `template/default_wap/images/flt.png` | 6148 | `0de7fe04e8aaceab245559905abcf3a559ebd0f4` |
| 234 | `template/default_wap/images/gb-reply.png` | 3741 | `c1d4403c38788065909f1fa24aec97c2c7ce0de2` |
| 235 | `template/default_wap/images/gb.png` | 3302 | `cfae69f89812aa5b6ee27a8933491580fec006fc` |
| 236 | `template/default_wap/images/gbreply.png` | 3094 | `2ff9a617ff483f2c4bcfd5a44d91c39291956ffc` |
| 237 | `template/default_wap/images/ico.png` | 2978 | `4fbce52eec681553416cc615ce696dbe9b45ae5a` |
| 238 | `template/default_wap/images/indexIconMap.png` | 6727 | `4f5dc9dbc1106356da5fcf197c396943fc39d047` |
| 239 | `template/default_wap/images/input.gif` | 65 | `25ef3943b55be72f2dec8c3a610bc7dc34fddcac` |
| 240 | `template/default_wap/images/inputa.gif` | 13132 | `ccb45206bbdb8a07b1ce235ae2232f9c2e3dbcce` |
| 241 | `template/default_wap/images/lazyload.gif` | 1553 | `085ccaecaf5fa5c34bc14cd2c2ed5cbbd8e25dcb` |
| 242 | `template/default_wap/images/logo.png` | 3515 | `6a6ff3b58a1a4c3f1beb6f670fa2ff414f5e1d7f` |
| 243 | `template/default_wap/images/news.png` | 2921 | `8803addba86521043c3e9ad65db3588b53c76854` |
| 244 | `template/default_wap/images/num.png` | 3389 | `a8cf713aea4b7c8964c2ee0b759e702c4ac23333` |
| 245 | `template/default_wap/images/rating-total.png` | 3271 | `2fc19051ea3da11fb8dfd1364f600c95e2379a51` |
| 246 | `template/default_wap/images/rating.png` | 4215 | `afd95a3ee3cdd4ab41f413c9f8d27633c101a47f` |
| 247 | `template/default_wap/images/score-bg.gif` | 2089 | `aa636f415690539468173aa49173b29f23dded2b` |
| 248 | `template/default_wap/images/score-scale.gif` | 525 | `1e20e72da2c8ad4c5e3d0f7ffe80e32f9fc61a05` |
| 249 | `template/default_wap/images/search.gif` | 247 | `e2bf4fa3315e6f40ea257ea3df669478f78a9362` |
| 250 | `template/default_wap/images/star-min.gif` | 1975 | `2013e1cc7c784a688e94fcd6b1137f274fcb5432` |
| 251 | `template/default_wap/images/tabx.png` | 919 | `7f101915c2666002f358ccaebe3c95f1948e98ca` |
| 252 | `template/default_wap/images/ui-icon.gif` | 9412 | `82c4a29181c863191ec374ab3d21759c23294b33` |
| 253 | `template/default_wap/images/ui-icony.png` | 2858 | `462358159a62ef45e3e76e7513856ebe96f308d8` |
| 254 | `template/default_wap/images/user/go-back.png` | 1328 | `173e33f994148deda8f71102c1bb945ba19b8324` |
| 255 | `template/default_wap/images/user/i-img-cod.png` | 1872 | `b041466cc52f9ae766d195ec86557576db1dac3d` |
| 256 | `template/default_wap/images/user/i-pwd.png` | 1564 | `5ce6e65e87430e5adfd36b87950001368c4e67d1` |
| 257 | `template/default_wap/images/user/i-user.png` | 4631 | `635f27303b7d3599950d065979a42185f3c788c9` |
| 258 | `template/default_wap/images/user/i-user2.png` | 575 | `fea486884b5357b76123ee91358fc4d3a1f72a32` |
| 259 | `template/default_wap/images/user/mb-collect.png` | 1672 | `7ef9ddabf926c5ea8c6b5cebc45ad30cf7d55b8a` |
| 260 | `template/default_wap/images/user/mb-credit.png` | 2006 | `65b6da81ef568b659c45a0e16268cacaf4cdd333` |
| 261 | `template/default_wap/images/user/mb-myorder.png` | 1549 | `611add88a32ab9428d2ec89370563de35dd8ef0d` |
| 262 | `template/default_wap/images/user/mb-user.png` | 1792 | `dab1f2a4540adb1fb8d9a4944f638b882915a11d` |
| 263 | `template/default_wap/images/user/member.png` | 4798 | `130637f9a09dd31c432a91e014b2b767c44f1998` |
| 264 | `template/default_wap/images/user/red-bg.jpg` | 6370 | `235f136e3e81c1ac9ca690165050492c6fad9f12` |
| 265 | `template/default_wap/info.ini` | 128 | `9c030d6a7be2e8a0184f92f32ec4a88822c343a7` |
| 266 | `template/default_wap/js/down.js` | 127072 | `84bcc5a40d2f8f69b4a5a5c86a384ed99423539b` |
| 267 | `template/default_wap/js/iscroll.js` | 11894 | `9db562ff15285237b17c647ace17f204c7dc46d8` |
| 268 | `template/default_wap/js/jquery-1.9.1.min.js` | 92629 | `006e953102ded2db8e217e4507de3baa8bcc976d` |
| 269 | `template/default_wap/js/jquery-autocomplete.js` | 14086 | `47eebe8e001a1e1d60644054acbbcff9b8634cc0` |
| 270 | `template/default_wap/js/jquery.base.js` | 6484 | `457ecd0bb139c1eb2c52bfcbff6fe67cc3b77a24` |
| 271 | `template/default_wap/js/jquery.glide.js` | 14204 | `ed13df64d8da4952ddc10a27cc30b673872db540` |
| 272 | `template/default_wap/js/jquery.js` | 84230 | `acd6e62a49463b33fef4944239650e53f784fef4` |
| 273 | `template/default_wap/js/list.js` | 1230 | `cbfb5f117c133df7df449e642b7d8342e555c381` |
| 274 | `template/default_wap/js/zepto.js` | 29999 | `e1ecafe27aeb8b1650d0795232cb60238588b26a` |


## E.4 只读取证事实（grep，不是完整扫描；完整扫描见主文件 PRE-5）

| 编号 | 事实 | 证据 |
| --- | --- | --- |
| EF-1 | `default_pc/html` 92 个 `.html`，`default_wap/html` 76 个 `.html`；`js/`、`css/`、`ads/` 下的文件没有 `{maccms` 字样 | `grep -rl '{maccms'` 对两个目录的 `js`、`css`、`ads` 命中 0 个文件 |
| EF-2 | 含 PHP 代码的模板：`default_pc` 4 个——`html/art/detail.html:33`（`{php}`）、`html/vod/plot.html:27`（`{php}`）、`html/public/jump.html:18`（`<?php echo($url);?>`）、`html/vod/copyright.html:19`（`<?php echo($obj['vod_jumpurl']);?>`）；`default_wap` 2 个——`html/public/jump.html:18`、`html/vod/copyright.html:19` | `grep -rnE '<\?php|\{php\}|<\?='` |
| EF-3 | `{maccms:…}` 标签出现次数：`default_pc` 为 `vod` 60、`foreach` 49、`type` 9、`art` 7、`link` 6、`actor` 6、`website` 5、`comment` 2、`topic` 1、`gbook` 1、`for` 1；`default_wap` 为 `vod` 28、`foreach` 27、`type` 7、`comment` 2、`art` 2、`topic` 1、`gbook` 1、`for` 1、`link` 1，另有 `path`、`siteaid`、`sitetid`、`siteid` 各 1（`default_wap/html/user/include.html:2`） | `grep -rhoE '\{maccms:[a-z]+'` |
| EF-4 | 在仓库 `php/application/` 里搜 `siteaid`、`sitetid`，没有定义；`{maccms:path}`、`{maccms:siteid}` 也不在 `Maccms.php` 的 `$tags` 数组（19 个标签）里 | `grep -rn` 对 `php/application` |
| EF-5 | 其他语法出现次数（两个目录合计）：`{include` 234、`{if` 210、`{volist` 35、`{else` 35、`{elseif` 18、`{empty` 10、`{foreach` 4、`{switch` 1；`{:` 函数调用以 `url`（303）、`mac_url_type`（102）、`mac_url_vod_detail`（101）、`mac_url`（55）、`mac_url_img`（51）为主，另有 `mac_url_art_detail`、`mac_url_vod_play`、`mac_url_plot_detail`、`mac_url_website_detail`、`mac_url_topic_detail`、`mac_url_search`、`mac_url_vod_plot`、`mac_url_website_index`、`mac_url_topic_index`、`mac_url_role_detail`、`mac_url_art_index`、`mac_url_actor_detail` | `grep -rhoE` |
| EF-6 | `default_pc/info.ini` 写有 `adsdir = ads`；`default_wap` 没有 `ads/` 目录 | 文件内容与 E.3 清单 |
| EF-7 | 官方包的 `html/` 下 **没有** 下列 `php/` 控制器会请求的模板：桌面 41 个、手机 57 个（逐项见附表 B.1.1 的“桌面包”“手机包”两列），例如两个包都没有 `search/index`、`help/index`、`note/index`、`public/verify`、`*/ajax_*`、`user/comment`、`user/gbook` | 附表 B.1.1，由脚本对 E.3 的 `html/` 路径逐条核对 |
