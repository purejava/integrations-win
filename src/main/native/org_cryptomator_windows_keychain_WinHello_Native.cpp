#include <jni.h>
#include "org_cryptomator_windows_keychain_WinHello_Native.h"
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Security.Credentials.h>
#include <winrt/Windows.Security.Cryptography.h>
#include <winrt/Windows.Security.Cryptography.Core.h>
#include <winrt/Windows.Storage.Streams.h>
#include <windows.h>
#include <string>
#include <vector>
#include <stdexcept>
#include <iostream>

using namespace winrt;
using namespace Windows::Security::Credentials;
using namespace Windows::Security::Cryptography;
using namespace Windows::Security::Cryptography::Core;
using namespace Windows::Storage::Streams;

const std::wstring s_winHelloKeyName{L"cryptomator_winhello"};

// Helper method for convertion
std::vector<uint8_t> jbyteArrayToVector(JNIEnv* env, jbyteArray array) {
    if (array == nullptr) {
        return std::vector<uint8_t>();
    }
    jsize length = env->GetArrayLength(array);
    std::vector<uint8_t> result(length);
    env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(result.data()));
    return result;
}

jbyteArray vectorToJbyteArray(JNIEnv* env, const std::vector<uint8_t>& vec) {
    jbyteArray array = env->NewByteArray(vec.size());
    env->SetByteArrayRegion(array, 0, vec.size(), reinterpret_cast<const jbyte*>(vec.data()));
    return array;
}

IBuffer vectorToIBuffer(const std::vector<uint8_t>& vec) {
    DataWriter writer;
    writer.WriteBytes(array_view<const uint8_t>(vec));
    return writer.DetachBuffer();
}

std::vector<uint8_t> iBufferToVector(const IBuffer& buffer) {
    auto reader = DataReader::FromBuffer(buffer);
    if (!buffer || reader.UnconsumedBufferLength() == 0) {
        return {};
    }
    std::vector<uint8_t> result(reader.UnconsumedBufferLength());
    reader.ReadBytes(array_view<uint8_t>(result));
    return result;
}

bool deriveEncryptionKey(const std::vector<uint8_t>& challenge, std::vector<uint8_t>& key){
    auto challengeBuffer = CryptographicBuffer::CreateFromByteArray(
        array_view<const uint8_t>(challenge.data(), challenge.size()));

    try {
    // The first time this is used a key-pair will be generated using the common name
    auto result = KeyCredentialManager::RequestCreateAsync(s_winHelloKeyName,
        KeyCredentialCreationOption::FailIfExists).get();

        if (result.Status() == KeyCredentialStatus::CredentialAlreadyExists) {
            result = KeyCredentialManager::OpenAsync(s_winHelloKeyName).get();
        } else if (result.Status() != KeyCredentialStatus::Success) {
            std::cout << "Failed to create Windows Hello credential." << std::endl;
            return false;
        }

        const auto signature = result.Credential().RequestSignAsync(challengeBuffer).get();
        if (signature.Status() != KeyCredentialStatus::Success) {
            if (signature.Status() != KeyCredentialStatus::UserCanceled) {
                std::cout << "Failed to sign challenge using Windows Hello." << std::endl;
            }
            return false;
        }

        // Use the SHA-256 hash of the challenge signature as the encryption key
        const auto response = signature.Result();
        HashAlgorithmProvider hashProvider = HashAlgorithmProvider::OpenAlgorithm(HashAlgorithmNames::Sha256());
        IBuffer hashBuffer = hashProvider.HashData(response);
        key = iBufferToVector(hashBuffer);
        return true;

    } catch (winrt::hresult_error const& ex) {
        std::cout << winrt::to_string(ex.message()) << std::endl;
        return false;
    }
}

// Encrypts data using Windows Hello KeyCredentialManager API and derived key from signed salt
jbyteArray JNICALL Java_org_cryptomator_windows_keychain_WinHello_00024Native_setEncryptionKey
(JNIEnv* env, jobject obj, jbyteArray cleartext, jbyteArray salt) {
    try {
        // Convert Java byte arrays to C++ vectors
        std::vector<uint8_t> cleartextVec = jbyteArrayToVector(env, cleartext);
        std::vector<uint8_t> saltVec = jbyteArrayToVector(env, salt);

        // Initialize Windows Runtime
        init_apartment();

        // Take the random challenge and sign it by Windows Hello
        // to create the key. The challenge is also used as the IV.
        std::vector<uint8_t> key;
        if (!deriveEncryptionKey(saltVec, key)) {
            throw std::runtime_error("Failed to generate the encryption key.");
        }

        hstring algorithmName = SymmetricAlgorithmNames::AesCbcPkcs7();
        SymmetricKeyAlgorithmProvider aesProvider = SymmetricKeyAlgorithmProvider::OpenAlgorithm(algorithmName);
        auto keyMaterial = CryptographicBuffer::CreateFromByteArray(array_view<const uint8_t>(key.data(), key.size()));
        CryptographicKey aesKey = aesProvider.CreateSymmetricKey(keyMaterial);
        auto dataBuffer = CryptographicBuffer::CreateFromByteArray(array_view<const uint8_t>(cleartextVec.data(), cleartextVec.size()));
        IBuffer encryptedBuffer = CryptographicEngine::Encrypt(aesKey, dataBuffer, keyMaterial);

        return vectorToJbyteArray(env, iBufferToVector(encryptedBuffer));

    } catch (const std::exception& e) {
        std::cout << "Error: " << e.what() << std::endl;
        jbyteArray byteArray = env->NewByteArray(0);
        return byteArray;
    }
}

// Decrypts data using Windows Hello KeyCredentialManager API and derived key from signed salt
jbyteArray JNICALL Java_org_cryptomator_windows_keychain_WinHello_00024Native_getEncryptionKey
(JNIEnv* env, jobject obj, jbyteArray ciphertext, jbyteArray salt) {
    try {
        // Convert Java byte arrays to C++ vectors
        std::vector<uint8_t> ciphertextVec = jbyteArrayToVector(env, ciphertext);
        std::vector<uint8_t> saltVec = jbyteArrayToVector(env, salt);

        // Initialize Windows Runtime
        init_apartment();

        // Take the random challenge and sign it by Windows Hello
        // to create the key. The challenge is also used as the IV.
        std::vector<uint8_t> key;
        if (!deriveEncryptionKey(saltVec, key)) {
            throw std::runtime_error("Failed to generate the encryption key.");
        }

        hstring algorithmName = SymmetricAlgorithmNames::AesCbcPkcs7();
        SymmetricKeyAlgorithmProvider aesProvider = SymmetricKeyAlgorithmProvider::OpenAlgorithm(algorithmName);
        auto keyMaterial = CryptographicBuffer::CreateFromByteArray(array_view<const uint8_t>(key.data(), key.size()));
        CryptographicKey aesKey = aesProvider.CreateSymmetricKey(keyMaterial);
        auto dataBuffer = CryptographicBuffer::CreateFromByteArray(array_view<const uint8_t>(ciphertextVec.data(), ciphertextVec.size()));
        IBuffer decryptedBuffer = CryptographicEngine::Decrypt(aesKey, dataBuffer, keyMaterial);

        return vectorToJbyteArray(env, iBufferToVector(decryptedBuffer));

    } catch (const std::exception& e) {
        std::cout << "Error: " << e.what() << std::endl;
        jbyteArray byteArray = env->NewByteArray(0);
        return byteArray;
    }
}
