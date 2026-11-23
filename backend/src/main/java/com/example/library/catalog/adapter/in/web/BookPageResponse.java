package com.example.library.catalog.adapter.in.web;

import java.util.List;

record BookPageResponse(List<BookResponse> content, int page, int size, long totalElements, int totalPages) {
}
