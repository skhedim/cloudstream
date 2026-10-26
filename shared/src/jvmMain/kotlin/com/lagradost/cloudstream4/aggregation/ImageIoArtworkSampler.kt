package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream4.domain.media.ArtworkSignature
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Desktop decoder for [SearchArtworkSignatures]: bilinear downscale, like Android's filtered scaling. */
object ImageIoArtworkSampler : ArtworkSampler {
    override fun sample(bytes: ByteArray): ArtworkSignature? {
        val image = ImageIO.read(bytes.inputStream()) ?: return null
        if (image.width <= 0 || image.height <= 0) return null
        val small = BufferedImage(ArtworkSignature.WIDTH, ArtworkSignature.HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = small.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(image, 0, 0, ArtworkSignature.WIDTH, ArtworkSignature.HEIGHT, null)
        } finally { graphics.dispose() }
        val pixels = small.getRGB(0, 0, ArtworkSignature.WIDTH, ArtworkSignature.HEIGHT, null, 0, ArtworkSignature.WIDTH)
        return ArtworkSignature(pixels.flatMap { listOf((it shr 16) and 255, (it shr 8) and 255, it and 255) },
            image.width.toDouble() / image.height)
    }
}
