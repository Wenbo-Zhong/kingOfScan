# PDFBox's optional JP2Android plugin is not bundled. JPX decoding checks for it
# with Class.forName and reports MissingImageReaderException; exports use JPEGFactory.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
