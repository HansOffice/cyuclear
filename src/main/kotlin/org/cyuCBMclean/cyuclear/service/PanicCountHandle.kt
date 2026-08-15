package org.cyuCBMclean.cyuclear.service

interface PanicCountHandle {
    fun cancel()
}

object NoopPanicCountHandle : PanicCountHandle {
    override fun cancel() = Unit
}
