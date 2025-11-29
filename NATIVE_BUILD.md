# Building Native Applications

This project is configured to create native installers for Windows, Linux, and Mac using Maven and jpackage.

## Prerequisites
- **Java 14+** (fjpackage is included since Java 14)
- **Maven 3.6+**
- **Platform-specific tools**:
  - **Windows**: WiX Toolset 3.x (for .msi installers) or download from https://wixtoolset.org/
  - **macOS**: Xcode command line tools
  - **Linux**: rpm-build (for .rpm) or dpkg-dev (for .deb)

## Build Commands

### Build JAR only
```bash
mvn clean package
```

### Build Native Application (Current Platform)

**Option 1: Standard Build (bundles full JRE)**
```bash
mvn clean package
mvn jpackage:jpackage
```

**Option 2: Optimized Build (custom JRE with jlink - smaller size)**
```bash
mvn clean package
mvn jlink:jlink
mvn jpackage:jpackage@with-runtime
```

For Option 2, add this profile to your `pom.xml` or use Option 1 for simplicity.

The native installer will be created in `target/dist/`

### Quick Native Build (Recommended)
```bash
mvn clean package && mvn jpackage:jpackage
```

## Platform-Specific Installers

### Windows
- Creates `.exe` (executable) and optionally `.msi` installer
- Includes desktop shortcut and start menu entry
- Located in: `target/dist/`

### macOS
- Creates `.dmg` disk image and/or `.pkg` installer
- Located in: `target/dist/`

### Linux
- Creates `.deb` (Debian/Ubuntu) or `.rpm` (Red Hat/Fedora)
- Includes desktop shortcut
- Located in: `target/dist/`

## Configuration

The native application configuration is in `pom.xml`:

- **App Name**: NotePile
- **Version**: 1.0.0
- **Main Class**: com.unhuman.notepile.Main
- **Vendor**: Unhuman

### Customizing

You can customize the build by modifying properties in `pom.xml`:
- `app.name` - Application name
- `app.version` - Application version
- `main.class` - Main class with main() method

### JLink Modules

The following Java modules are included in the runtime image:
- `java.base` - Core Java functionality
- `java.desktop` - Swing/AWT GUI components
- `java.logging` - Logging API

Add more modules in the `maven-jlink-plugin` configuration if needed.

## Output

After building, you'll find:
- **JAR file**: `target/NotePile-1.0.0-SNAPSHOT.jar`
- **Dependencies**: `target/lib/`
- **Native installer**: `target/dist/` (platform-specific format)
- **Runtime image**: `target/runtime/` (custom JRE)

## Troubleshooting

### "jpackage command not found"
Make sure you're using Java 14 or later. Check with `java -version`.

### Windows: "WiX Toolset required"
Download and install WiX Toolset from https://wixtoolset.org/

### macOS: Code signing issues
You may need to configure code signing in the jpackage plugin or sign the app manually after creation.

### Module errors
If you get module-related errors, ensure all required Java modules are listed in the `maven-jlink-plugin` configuration.

