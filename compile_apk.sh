#  Compilation sans Android Studio :
#  cd android/
#  ./gradlew assembleDebug          # compile → app/build/outputs/apk/debug/writhdeck-debug.apk
#  ./gradlew installDebug           # compile + installe via adb (appareil connecté)
#  ./gradlew assembleDebug --daemon # daemon Gradle = relances plus rapides (~5s au lieu de 50s)



 ./gradlew assembleDebug 
# ./gradlew assembleRelease

# apksigner sign \
#    --ks writhdeck.jks \
#    --ks-key-alias writhdeck \
#    --out writhdeck-release.apk \
#    app/build/outputs/apk/release/writhdeck-release.apk
	


# ./gradlew bundleRelease  # pour générer .aab 

printf "=>\n\napp/build/outputs/apk/debug/writhdeck-debug.apk \n\n" 
# printf "=>\n\napp/build/outputs/apk/release/ \n\n" 



