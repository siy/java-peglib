module com.example.demo {
    requires java.base;
    requires transitive java.sql;
    exports com.example.api;
    exports com.example.internal to com.example.client;
    opens com.example.impl;
    uses com.example.api.Service;
    provides com.example.api.Service with com.example.impl.ServiceImpl;
}
