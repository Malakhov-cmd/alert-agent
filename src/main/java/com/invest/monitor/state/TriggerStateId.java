package com.invest.monitor.state;

import java.io.Serializable;
import java.util.Objects;

public class TriggerStateId implements Serializable {

    private String isin;
    private String frequency;

    public TriggerStateId() {}

    public TriggerStateId(String isin, String frequency) {
        this.isin      = isin;
        this.frequency = frequency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TriggerStateId that)) return false;
        return Objects.equals(isin, that.isin) && Objects.equals(frequency, that.frequency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isin, frequency);
    }
}
