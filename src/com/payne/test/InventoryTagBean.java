package com.payne.test;

import com.payne.reader.bean.receive.InventoryTag;

import java.io.Serializable;

public class InventoryTagBean implements Serializable {
    private InventoryTag inventoryTag;
    private int mP;
    private int mTimes;
    private String mTimeStr;

    public InventoryTagBean(InventoryTag bean, int p) {
        inventoryTag = bean;
        mP = p;
        mTimes = 1;
        mTimeStr = "1";
    }

    public void addTimes() {
        mTimes++;
        mTimeStr = String.valueOf(mTimes);
    }

    public void setInventoryTag(InventoryTag inventoryTag) {
        this.inventoryTag = inventoryTag;
    }

    public String getEpc() {
        return inventoryTag.getEpc();
    }

    public String getPc() {
        return inventoryTag.getPc();
    }

    public String getTimes() {
        return mTimeStr;
    }

    public String getRssi() {
        return inventoryTag.getRssi() + "dBm";
    }

    public int getAntId() {
        return inventoryTag.getAntId();
    }

    public String getFreq() {
        return inventoryTag.getFreq();
    }

    public String getPhase() {
        return String.valueOf(inventoryTag.getPhase());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InventoryTagBean)) {
            return false;
        }

        InventoryTagBean that = (InventoryTagBean) o;

        if (inventoryTag != null) {
            return inventoryTag.getEpc().equals(that.inventoryTag.getEpc());
        }
        return false;
    }

    public int getPosition() {
        return mP;
    }

    @Override
    public String toString() {
        return "InventoryTagBean{mP=" + mP + ",mTimes=" + mTimes + "," + inventoryTag + '}';
    }
}