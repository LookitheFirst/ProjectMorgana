package interfaces

interface ChatInterface {
    fun onUserUpdateChannels(userId: Long)
    fun onChannelsUpdated()
    fun onUserUpdateProfile(userId: Long)
}
