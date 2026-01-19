package com.bitchat.viewmodel

import org.junit.Rule

open class BaseViewModelTest {

    @get:Rule
    val instantExecutorRule = TestCoroutineRule()

}
