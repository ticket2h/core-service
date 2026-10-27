package com.ticket2h.inventory;

/**
 * Ảnh chụp sơ đồ ghế đã đổi sẵn sang JSON (ADR-024, ADR-025): 120.000 ghế là khoảng 20MB, đổi một lần mỗi giây cho
 * mọi người xem chứ không mỗi request một lần. {@code seq}: số thứ tự thay đổi đã phát (đọc TRƯỚC khi đọc database),
 * mọi thay đổi có số {@code <= seq} đã nằm trong ảnh chụp. {@code etag}: băm của {@code json}, trình duyệt gửi lại
 * trong {@code If-None-Match}, không đổi thì nhận 304 không có body.
 */
record SeatMapSnapshot(long seq, byte[] json, String etag) {}
