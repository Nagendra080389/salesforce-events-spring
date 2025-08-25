#!/bin/bash

echo "======================================"
echo "Fixing Salesforce Events Build Issues"
echo "======================================"

# Step 1: Check if proto directory exists
echo "Step 1: Checking proto directory..."
if [ ! -d "src/main/proto" ]; then
    echo "Creating proto directory..."
    mkdir -p src/main/proto
fi

# Step 2: Check if proto file exists
echo "Step 2: Checking for proto file..."
if [ ! -f "src/main/proto/pubsub_api.proto" ]; then
    echo ""
    echo "❌ ERROR: Proto file not found!"
    echo ""
    echo "You MUST copy the pubsub_api.proto file from your existing repository:"
    echo ""
    echo "  cp /path/to/your/java/src/main/proto/pubsub_api.proto src/main/proto/"
    echo ""
    echo "The proto file should be from the Salesforce Pub/Sub API examples repository"
    echo "that you provided. This file is REQUIRED for the application to work."
    echo ""
    read -p "Have you copied the proto file? (y/n): " proto_copied

    if [ "$proto_copied" != "y" ]; then
        echo "Please copy the proto file and run this script again."
        exit 1
    fi

    # Check again
    if [ ! -f "src/main/proto/pubsub_api.proto" ]; then
        echo "Proto file still not found. Please ensure it's copied correctly."
        exit 1
    fi
fi

echo "✅ Proto file found!"

# Step 3: Clean any previous build artifacts
echo ""
echo "Step 3: Cleaning previous build artifacts..."
mvn clean
rm -rf target/

# Step 4: Generate protobuf classes explicitly
echo ""
echo "Step 4: Generating protobuf classes..."
mvn protobuf:compile protobuf:compile-custom

# Check if generation was successful
if [ ! -d "target/generated-sources/protobuf" ]; then
    echo "❌ Failed to generate protobuf classes!"
    echo "Trying alternative approach..."

    # Try with explicit goals
    mvn generate-sources
fi

# Step 5: Verify generated classes
echo ""
echo "Step 5: Verifying generated classes..."
if [ -d "target/generated-sources/protobuf/java/com/salesforce/eventbus/protobuf" ]; then
    echo "✅ Protobuf Java classes generated successfully!"
    ls -la target/generated-sources/protobuf/java/com/salesforce/eventbus/protobuf/ | head -10
else
    echo "❌ Protobuf classes not found in expected location!"
fi

if [ -d "target/generated-sources/protobuf/grpc-java/com/salesforce/eventbus/protobuf" ]; then
    echo "✅ gRPC Java classes generated successfully!"
    ls -la target/generated-sources/protobuf/grpc-java/com/salesforce/eventbus/protobuf/ | head -5
else
    echo "❌ gRPC classes not found in expected location!"
fi

# Step 6: Compile the project
echo ""
echo "Step 6: Compiling the project..."
mvn compile

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ BUILD SUCCESSFUL!"
    echo ""
    echo "You can now run the application with:"
    echo "  mvn spring-boot:run"
    echo ""
    echo "Or use the run script:"
    echo "  ./run.sh"
else
    echo ""
    echo "❌ Build still failing. Please check:"
    echo "1. The proto file is correctly placed in src/main/proto/"
    echo "2. The proto file is the correct version from Salesforce"
    echo "3. Your Maven and Java versions are correct"
    echo ""
    echo "Java version:"
    java -version
    echo ""
    echo "Maven version:"
    mvn -version
fi