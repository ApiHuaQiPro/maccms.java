# B09 WebP 头像测试样本的生成脚本（18 文档第 14 节）。用 Pillow 12.3.0（自带 libwebp）在本目录运行：python generate.py
# 样本都是程序合成的纯色/渐变图，不含任何外部素材。伪造与截断的样本由 PortraitImagesWebPTest 在内存里构造，不在此目录。
from PIL import Image


def halves(mode):
    """300x200：左半全透明，右半不透明红。"""
    im = Image.new(mode, (300, 200), (0, 0, 0, 0))
    im.paste((255, 0, 0, 255), (150, 0, 300, 200))
    return im


gradient = Image.new("RGB", (300, 200))
gradient.putdata([(x * 255 // 299, y * 255 // 199, 128) for y in range(200) for x in range(300)])
gradient.save("lossy.webp", "WEBP", quality=60, method=4)
halves("RGBA").save("lossless-alpha.webp", "WEBP", lossless=True)
halves("RGBA").save("lossy-alpha.webp", "WEBP", quality=80)
# 两帧动图 120x80：第一帧蓝，第二帧绿
Image.new("RGB", (120, 80), (0, 0, 255)).save("animated.webp", "WEBP", save_all=True, lossless=True, loop=0,
                                              append_images=[Image.new("RGB", (120, 80), (0, 255, 0))],
                                              duration=[100, 100])
# 恰好等于与超过 WebP 像素上限 2048x2048 的合法无损图
Image.new("RGB", (2048, 2048), (10, 20, 30)).save("at-cap-2048x2048.webp", "WEBP", lossless=True)
Image.new("RGB", (2049, 2048), (10, 20, 30)).save("over-cap-2049x2048.webp", "WEBP", lossless=True)
