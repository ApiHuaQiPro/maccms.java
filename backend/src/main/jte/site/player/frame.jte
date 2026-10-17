@import com.maccms.backend.site.web.view.PlayerFrame
@import gg.jte.Content
@param PlayerFrame frame
@param Content content
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="noindex">
  <title>${frame.title()}</title>
  <link rel="stylesheet" href="${frame.stylesheet().href()}">
</head>
<body class="player-frame-body">
${content}
</body>
</html>
