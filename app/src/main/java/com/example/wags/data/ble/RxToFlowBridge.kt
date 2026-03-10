package com.example.wags.data.ble

import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.BackpressureStrategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.rx3.asFlow

fun <T : Any> Observable<T>.toKotlinFlow(): Flow<T> =
    this.asFlow()

fun <T : Any> Flowable<T>.toKotlinFlow(): Flow<T> =
    this.toObservable().asFlow()
