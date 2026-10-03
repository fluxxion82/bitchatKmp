package com.bitchat.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The verification column of BIP340's published vectors, rows 0-14 of bip-0340/test-vectors.csv,
 * against every platform's `schnorrVerify`.
 *
 * Signing vectors need the platform CSPRNG under test control, so `SchnorrSigningVectorsTest`
 * runs them on the JVM and Android only. Verification needs nothing but the inputs, so these run
 * wherever commonTest runs: BouncyCastle arithmetic on the JVM and Android, libsecp256k1 on Apple
 * and Linux. Rows 5-14 are rejections, each of a different mistake a verifier can make - a public
 * key off the curve or past the field size, an R with odd Y, a negated message or s, r or s not
 * reduced, the point at infinity - and each must come back false rather than throw. Copied as
 * published.
 */
class SchnorrVerificationVectorsTest {

    private class Vector(
        val index: Int,
        val publicKey: String,
        val message: String,
        val signature: String,
        val verifies: Boolean,
        val comment: String,
    )

    private val vectors = listOf(
        Vector(
            index = 0,
            publicKey = "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
            message = "0000000000000000000000000000000000000000000000000000000000000000",
            signature = "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA8215" +
                "25F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0",
            verifies = true,
            comment = "",
        ),
        Vector(
            index = 1,
            publicKey = "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            message = "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            signature = "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE3341" +
                "8906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A",
            verifies = true,
            comment = "",
        ),
        Vector(
            index = 2,
            publicKey = "DD308AFEC5777E13121FA72B9CC1B7CC0139715309B086C960E18FD969774EB8",
            message = "7E2D58D8B3BCDF1ABADEC7829054F90DDA9805AAB56C77333024B9D0A508B75C",
            signature = "5831AAEED7B44BB74E5EAB94BA9D4294C49BCF2A60728D8B4C200F50DD313C1B" +
                "AB745879A5AD954A72C45A91C3A51D3C7ADEA98D82F8481E0E1E03674A6F3FB7",
            verifies = true,
            comment = "",
        ),
        Vector(
            index = 3,
            publicKey = "25D1DFF95105F5253C4022F628A996AD3A0D95FBF21D468A1B33F8C160D8F517",
            message = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            signature = "7EB0509757E246F19449885651611CB965ECC1A187DD51B64FDA1EDC9637D5EC" +
                "97582B9CB13DB3933705B32BA982AF5AF25FD78881EBB32771FC5922EFC66EA3",
            verifies = true,
            comment = "test fails if msg is reduced modulo p or n",
        ),
        Vector(
            index = 4,
            publicKey = "D69C3509BB99E412E68B0FE8544E72837DFA30746D8BE2AA65975F29D22DC7B9",
            message = "4DF3C3F68FCC83B27E9D42C90431A72499F17875C81A599B566C9889B9696703",
            signature = "00000000000000000000003B78CE563F89A0ED9414F5AA28AD0D96D6795F9C63" +
                "76AFB1548AF603B3EB45C9F8207DEE1060CB71C04E80F593060B07D28308D7F4",
            verifies = true,
            comment = "",
        ),
        Vector(
            index = 5,
            publicKey = "EEFDEA4CDB677750A420FEE807EACF21EB9898AE79B9768766E4FAA04A2D4A34",
            message = "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            signature = "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769" +
                "69E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B",
            verifies = false,
            comment = "public key not on the curve",
        ),
        Vector(
            index = 6,
            publicKey = "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            message = "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            signature = "FFF97BD5755EEEA420453A14355235D382F6472F8568A18B2F057A1460297556" +
                "3CC27944640AC607CD107AE10923D9EF7A73C643E166BE5EBEAFA34B1AC553E2",
            verifies = false,
            comment = "has_even_y(R) is false",
        ),
        Vector(
            index = 7,
            publicKey = "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            message = "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            signature = "1FA62E331EDBC21C394792D2AB1100A7B432B013DF3F6FF4F99FCB33E0E1515F" +
                "28890B3EDB6E7189B630448B515CE4F8622A954CFE545735AAEA5134FCCDB2BD",
            verifies = false,
            comment = "negated message",
        ),
        Vector(
            index = 8,
            publicKey = "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            message = "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            signature = "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769" +
                "961764B3AA9B2FFCB6EF947B6887A226E8D7C93E00C5ED0C1834FF0D0C2E6DA6",
            verifies = false,
            comment = "negated s value",
        ),
        Vector(
            index = 9,
            publicKey = "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            message = "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            signature = "0000000000000000000000000000000000000000000000000000000000000000" +
                "123DDA8328AF9C23A94C1FEECFD123BA4FB73476F0D594DCB65C6425BD186051",
            verifies = false,
            comment = "sG - eP is infinite. Test fails in single verification if has_even_y(inf) is defined as true and x(inf) as 0",
        ),
        Vector(
            index = 10,
            publicKey = "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            message = "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            signature = "0000000000000000000000000000000000000000000000000000000000000001" +
                "7615FBAF5AE28864013C099742DEADB4DBA87F11AC6754F93780D5A1837CF197",
            verifies = false,
            comment = "sG - eP is infinite. Test fails in single verification if has_even_y(inf) is defined as true and x(inf) as 1",
        ),
        Vector(
            index = 11,
            publicKey = "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            message = "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            signature = "4A298DACAE57395A15D0795DDBFD1DCB564DA82B0F269BC70A74F8220429BA1D" +
                "69E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B",
            verifies = false,
            comment = "sig[0:32] is not an X coordinate on the curve",
        ),
        Vector(
            index = 12,
            publicKey = "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            message = "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            signature = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F" +
                "69E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B",
            verifies = false,
            comment = "sig[0:32] is equal to field size",
        ),
        Vector(
            index = 13,
            publicKey = "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            message = "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            signature = "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769" +
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",
            verifies = false,
            comment = "sig[32:64] is equal to curve order",
        ),
        Vector(
            index = 14,
            publicKey = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC30",
            message = "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            signature = "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769" +
                "69E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B",
            verifies = false,
            comment = "public key is not a valid X coordinate because it exceeds the field size",
        ),
    )

    @Test
    fun `verification agrees with every published vector`() {
        for (vector in vectors) {
            val verifies = Cryptography.schnorrVerify(
                vector.message.hexToByteArray(),
                vector.signature.lowercase(),
                vector.publicKey.lowercase(),
            )

            assertEquals(
                vector.verifies,
                verifies,
                "vector ${vector.index}: ${vector.comment.ifEmpty { "a valid signature" }}",
            )
        }
    }
}
