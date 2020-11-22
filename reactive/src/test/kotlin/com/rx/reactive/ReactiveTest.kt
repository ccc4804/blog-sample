package com.rx.reactive

import io.reactivex.rxkotlin.toFlowable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StopWatch
import javax.persistence.EntityManager

@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class ReactiveTest(
    private val orderRepository: OrderRepository,
    private val orderService: OrderService,
    private val entityManager: EntityManager
) {

    val sampleApi = OrderHttpClient()

    @Test
    fun `단일 스레드 작업`() {
        val stopWatch = StopWatch()
        val orders = givenOrders(1_000)
        stopWatch.start()

        orders
            .forEach {
                val result = sampleApi.doSomething(it.id!!)
                when {
                    result -> it.status = OrderStatus.COMPLETED
                    else -> it.status = OrderStatus.FAILED
                }
            }

        stopWatch.stop()
        println(stopWatch.totalTimeSeconds) // 1m 44s
    }

    @Test
    fun `멀티 스레드 작업`() {
        val stopWatch = StopWatch()
        val orders = givenOrders(1_000)
        stopWatch.start()

        orders
            .toFlowable()
            .parallel()
            .runOn(Schedulers.io())
            .map {
                val result = sampleApi.doSomething(it.id!!)
                Pair(result, it)
            }
            .sequential()
            .subscribe(
                {

                    when {
                        it.first -> it.second.status = OrderStatus.COMPLETED
                        else -> it.second.status = OrderStatus.FAILED
                    }
                },
                {
                    stopWatch.stop()
                    println(stopWatch.totalTimeSeconds)
                },
                {
                    println("Completed")
                }
            )

        runBlocking { delay(11_000) }
    }

    @Test
    fun `멀티 스레드 작업2`() {
        val stopWatch = StopWatch()
        val orders = givenOrders(100_000)
        stopWatch.start()

        val completedId = mutableListOf<Long>()
        val failedIds = mutableListOf<Long>()

        orders
            .toFlowable()
            .parallel()
            .runOn(Schedulers.io())
            .map {
//                println("Mapping orderId :${it.id} ${Thread.currentThread().name}")
                val result = sampleApi.doSomething(it.id!!)
                Pair(result, it)
            }
            .sequential()
            .subscribe(
                {
//                    println("Received orderId :${it.second.id} ${Thread.currentThread().name}")
                    when {
                        it.first -> completedId.add(it.second.id!!)
                        else -> failedIds.add(it.second.id!!)
                    }
                },
                {
                    it.printStackTrace()
                },
                {
                    orderService.updateStatus(OrderStatus.COMPLETED, completedId)
                    orderService.updateStatus(OrderStatus.FAILED, failedIds)
                    stopWatch.stop()
                    println(stopWatch.totalTimeSeconds)
                }
            )

        runBlocking { delay(11_000) }
    }

    private fun givenOrders(end: Int) = (1..end).map {
        Order(OrderStatus.READY)
    }.also {
        orderRepository.saveAll(it)
    }
}