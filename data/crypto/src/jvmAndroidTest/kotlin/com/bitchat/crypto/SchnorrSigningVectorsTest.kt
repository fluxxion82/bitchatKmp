package com.bitchat.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BIP340's published signing vectors, replayed through the signer with each vector's `aux_rand`
 * installed as the platform CSPRNG.
 *
 * `CsprngProvenanceTest` shows the auxiliary bytes come from the platform CSPRNG. This shows what
 * the signer does with them is BIP340's default signing algorithm - the one libsecp256k1 runs for
 * Apple and Linux - and not a derivation that happens to agree on one input. The rows are the
 * signing vectors of bip-0340/test-vectors.csv whose message is 32 bytes, the only length this
 * signer accepts, copied as published.
 */
class SchnorrSigningVectorsTest {

    private class Vector(
        val index: Int,
        val secretKey: String,
        val publicKey: String,
        val auxRand: String,
        val message: String,
        val signature: String,
    )

    private val vectors = listOf(
        Vector(
            index = 0,
            secretKey = "0000000000000000000000000000000000000000000000000000000000000003",
            publicKey = "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
            auxRand = "0000000000000000000000000000000000000000000000000000000000000000",
            message = "0000000000000000000000000000000000000000000000000000000000000000",
            signature = "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA8215" +
                "25F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0",
        ),
        Vector(
            index = 1,
            secretKey = "B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF",
            publicKey = "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            auxRand = "0000000000000000000000000000000000000000000000000000000000000001",
            message = "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            signature = "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE3341" +
                "8906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A",
        ),
        Vector(
            index = 2,
            secretKey = "C90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B14E5C9",
            publicKey = "DD308AFEC5777E13121FA72B9CC1B7CC0139715309B086C960E18FD969774EB8",
            auxRand = "C87AA53824B4D7AE2EB035A2B5BBBCCC080E76CDC6D1692C4B0B62D798E6D906",
            message = "7E2D58D8B3BCDF1ABADEC7829054F90DDA9805AAB56C77333024B9D0A508B75C",
            signature = "5831AAEED7B44BB74E5EAB94BA9D4294C49BCF2A60728D8B4C200F50DD313C1B" +
                "AB745879A5AD954A72C45A91C3A51D3C7ADEA98D82F8481E0E1E03674A6F3FB7",
        ),
        // "test fails if msg is reduced modulo p or n"
        Vector(
            index = 3,
            secretKey = "0B432B2677937381AEF05BB02A66ECD012773062CF3FA2549E44F58ED2401710",
            publicKey = "25D1DFF95105F5253C4022F628A996AD3A0D95FBF21D468A1B33F8C160D8F517",
            auxRand = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            message = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            signature = "7EB0509757E246F19449885651611CB965ECC1A187DD51B64FDA1EDC9637D5EC" +
                "97582B9CB13DB3933705B32BA982AF5AF25FD78881EBB32771FC5922EFC66EA3",
        ),
    )

    @Test
    fun `signing reproduces each vector's signature from its aux_rand`() {
        for (vector in vectors) {
            withInstalledCsprng(fill = InstalledCsprng.fillWith(vector.auxRand.hexToByteArray())) { csprng ->
                val signature = Cryptography.schnorrSign(vector.message.hexToByteArray(), vector.secretKey.lowercase())

                assertEquals(vector.signature.lowercase(), signature, "vector ${vector.index}: signature")
                assertEquals(listOf(32), csprng.draws.map { it.size }, "vector ${vector.index}: aux_rand draws")
            }
        }
    }

    @Test
    fun `each vector's public key derives from its secret key and verifies its signature`() {
        for (vector in vectors) {
            val publicKey = Cryptography.derivePublicKey(vector.secretKey.lowercase())

            assertEquals(vector.publicKey.lowercase(), publicKey, "vector ${vector.index}: public key")
            assertTrue(
                Cryptography.schnorrVerify(vector.message.hexToByteArray(), vector.signature.lowercase(), publicKey),
                "vector ${vector.index}: the published signature does not verify",
            )
        }
    }
}
