package tailucas.app.device;

public class Ring {
    public String acStatus;
    public String alarmState;
    public int batteryLevel;
    public String batteryStatus;
    public float brightness;
    public String chirps;
    public String commStatus;
    public String entrySecondsLeft;
    public String exitSecondsLeft;
    public String firmwareStatus;
    public String lastArmedBy;
    public String lastCommTime;
    public String lastUpdate;
    public String linkQuality;
    public String powerSave;
    public String serialNumber;
    public String tamperStatus;
    public float volume;
    @Override
    public String toString() {
        return "Ring [acStatus=" + acStatus + ", alarmState=" + alarmState + ", batteryLevel=" + batteryLevel
                + ", batteryStatus=" + batteryStatus + ", brightness=" + brightness + ", chirps=" + chirps
                + ", commStatus=" + commStatus + ", entrySecondsLeft=" + entrySecondsLeft + ", exitSecondsLeft="
                + exitSecondsLeft + ", firmwareStatus=" + firmwareStatus + ", lastArmedBy=" + lastArmedBy
                + ", lastCommTime=" + lastCommTime + ", lastUpdate=" + lastUpdate + ", linkQuality=" + linkQuality
                + ", powerSave=" + powerSave + ", serialNumber=" + serialNumber + ", tamperStatus=" + tamperStatus
                + ", volume=" + volume + "]";
    }
}
