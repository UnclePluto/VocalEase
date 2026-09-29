package fixture;

import androidx.work.Data;

final class UnsafeWork {
    Data leak(String privateUrl) {
        Data.Builder alias = new Data.Builder();
        return alias.putString("url", privateUrl).build();
    }
}
