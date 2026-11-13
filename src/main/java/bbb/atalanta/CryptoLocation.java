package bbb.atalanta;

/** Alpaca's explicitly documented historical crypto market-data locations. */
public enum CryptoLocation {
    US("us"), US_1("us-1"), US_2("us-2"), EU_1("eu-1"), BS_1("bs-1");

    private final String path;

    CryptoLocation(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
