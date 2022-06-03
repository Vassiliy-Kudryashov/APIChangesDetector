# APIChangesReporter
Reporter goes through library versions on Maven repository and report API changes step by step

Default libraries to investigate are JUnit 4, JUnit 5 and TestNG, default API classes to check and compare are the most useful `Assert.java`, `Assertions.java` and `ArrayAsserts.java`. Actually these famous unit testing frameworks went through many surprising API changes too.

Code is config ;) so modify `FRAMEWORKS` and `TARGET_API_CLASSES` fields as you need.

Designed for Java 8
