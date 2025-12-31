package test;

class ParseError {
    void validMethod() {
        int x = 1;
    }
    
    void anotherValid() {
        String s = "hello";
    }
    
    void brokenMethod() {
        int y = ;  // syntax error - missing expression
    }
}
