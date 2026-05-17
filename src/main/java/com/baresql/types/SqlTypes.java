package com.baresql.types;

public interface SqlTypes {
    sealed interface SqlType { }
    record SqlInt() implements SqlType { }
    record SqlText() implements SqlType { }
    record SqlJsonB() implements SqlType { }
    record SqlUuid() implements SqlType { }
    record SqlArray(SqlType elementType) implements SqlType { } // Restaurado!
}
