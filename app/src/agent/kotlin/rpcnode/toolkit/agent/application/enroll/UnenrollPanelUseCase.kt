package rpcnode.toolkit.agent.application.enroll

class UnenrollPanelUseCase(
    private val store: PanelEnrollmentStore,
)
{
    suspend operator fun invoke()
    {
        store.clear()
    }
}
