package com.subcast.dlna

/** Builds minimal DIDL-Lite metadata so the TV shows a title and media type. */
object DidlBuilder {

    fun build(url: String, title: String, mimeType: String): String {
        val t = escape(title)
        val u = escape(url)
        return (
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"1\" parentID=\"0\" restricted=\"1\">" +
                "<dc:title>$t</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"http-get:*:$mimeType:*\">$u</res>" +
                "</item></DIDL-Lite>"
            )
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
