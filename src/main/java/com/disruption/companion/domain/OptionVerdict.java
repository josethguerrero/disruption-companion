package com.disruption.companion.domain;

import java.util.List;

public record OptionVerdict(RebookOption option, boolean valid, List<String> reasons) {
}
