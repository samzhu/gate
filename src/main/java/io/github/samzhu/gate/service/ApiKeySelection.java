package io.github.samzhu.gate.service;

/**
 * API Key 選擇結果
 * 包含選中的 API Key 和其別名
 */
public record ApiKeySelection(
    String key,
    String alias
) {}
