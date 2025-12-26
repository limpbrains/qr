# keklol

A simple Kotlin library that provides a `revertx` function to reverse strings.

## Features

- `revertx(input: String)`: Reverses the given string

## Requirements

- JDK 17 or higher
- Gradle (if wrapper not present)

## Setup

If the Gradle wrapper is not present, generate it first:

```bash
gradle wrapper --gradle-version 8.5
```

Alternatively, you can use your local Gradle installation instead of `./gradlew` in the commands below.

## Build

To build the project:

```bash
./gradlew build
```

## Run Tests

To run the unit tests:

```bash
./gradlew test
```

To run tests with verbose output:

```bash
./gradlew test --info
```

## Usage

```kotlin
import com.keklol.revertx

fun main() {
    val original = "hello"
    val reversed = revertx(original)
    println(reversed)  // Output: olleh
}
```

## Project Structure

```
keklol/
├── build.gradle.kts
├── settings.gradle.kts
├── src/
│   ├── main/
│   │   └── kotlin/
│   │       └── com/
│   │           └── keklol/
│   │               └── StringUtils.kt
│   └── test/
│       └── kotlin/
│           └── com/
│               └── keklol/
│                   └── StringUtilsTest.kt
└── README.md
```
