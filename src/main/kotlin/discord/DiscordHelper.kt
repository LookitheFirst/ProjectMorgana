package discord

import database.DatabaseHelper
import domain
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.requests.restaction.PermissionOverrideAction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import types.Course
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class DiscordHelper(val guild: Guild, val databaseHelper: DatabaseHelper) {
    private val LOG: Logger = LoggerFactory.getLogger(this::class.java)
    
    private val permissions = mutableListOf(
            Permission.MESSAGE_READ
    )

    suspend fun inviteUser(user: User) {
        if (user.isBot) return
        LOG.info("Generating an invite for user ${user.asTag}")
        val token = databaseHelper.generateTokenForUser(user.idLong)
        kotlin.runCatching {
            user.openPrivateChannelAsync()
                .sendMessageAsync("Hi, I'm the channel management bot of the Matterleast Server, also known as Informatik W18 of the TU Vienna. Please go to https://$domain/login/token/$token to set channels")
        }
    }

    suspend fun PrivateChannel.sendMessageAsync(message: String, delayMilis: Long = 0): Message = suspendCoroutine { cont -> sendMessage(message).queueAfter(delayMilis, TimeUnit.MILLISECONDS) { cont.resume(it) } }

    suspend fun User.openPrivateChannelAsync(): PrivateChannel = suspendCoroutine { cont -> openPrivateChannel().queue { cont.resume(it) } }

    fun syncUsers() {
        LOG.info("Syncing users with database")
        val dbUserIds: List<Long> = databaseHelper.getUsers().map { it.userId }
        val serverUserIds: List<Long> = guild.members.map { it.user.idLong }
        val newUserIds: List<Long> = serverUserIds - dbUserIds
        GlobalScope.launch {
            newUserIds.forEach { userId ->
                val user = guild.members.find { it.user.idLong == userId }?.user
                if (user != null && !user.isBot) {
                    databaseHelper.addUser(types.User(user.idLong))
                    inviteUser(user)
                }
                delay(1000L)
            }
        }
    }

    fun updateAllChannelPermissions() {
        LOG.info("Updating all channel permissions")
        GlobalScope.launch {
            databaseHelper.getCourses().forEach { course ->
                val channels = databaseHelper.getChannelsForCourse(course.courseId).mapNotNull { channel -> guild.channels.find { it.idLong == channel.channelId } }
                val courseUserIds = databaseHelper.getUsersForCourse(course.courseId).map { it.userId }
                val members = guild.members.filter { courseUserIds.contains(it.user.idLong) }
                channels.forEach { channel ->
                    channel.permissionOverrides.filter { it.isMemberOverride }.forEach { permissionOverride ->
                        if (!members.contains(permissionOverride.member)) permissionOverride.delete().complete()
                    }
                    members.forEach { member ->
                        if (channel.permissionOverrides.filter { it.isMemberOverride }.find { it.member == member } == null) {
                            channel.safeAddPermissionOverrideAsync(member, permissions)
                        }
                    }
                }
            }
        }
    }

    fun updateUserChannels(userId: Long) {
        val member = guild.members.find { it.user.idLong == userId } ?: throw Exception("UserNotFoundException")
        LOG.info("Permission update initiated for user ${member.user.asTag}")
        val subscribedCourseIds = databaseHelper.getCoursesForUser(userId).map { it.courseId }
        val subscribedChannelIds = subscribedCourseIds.map { databaseHelper.getChannelsForCourse(it) }.flatten().map { it.channelId }
        GlobalScope.launch {
            guild.channels.forEach { channel ->
                if (subscribedChannelIds.contains(channel.idLong)) {
                    channel.safeAddPermissionOverrideAsync(member, permissions)
                } else {
                    channel.safeRemovePermissionOverrideAsync(member)
                }
                delay(100L)
            }
        }
    }

    suspend fun GuildChannel.safeAddPermissionOverrideAsync(member: Member, permissions: MutableList<Permission>, delayMilis: Long = 0): Boolean = if (this.getPermissionOverride(member) == null) suspendCoroutine { cont ->
        val override = createPermissionOverride(member)
        override.setAllow(permissions).queueAfter(delayMilis, TimeUnit.MILLISECONDS)  { cont.resume(true) }
    } else false

    suspend fun GuildChannel.safeRemovePermissionOverrideAsync(member: Member, delayMilis: Long = 0): Boolean = if (this.getPermissionOverride(member) != null) suspendCoroutine { cont ->
        getPermissionOverride(member).delete().queueAfter(delayMilis, TimeUnit.MILLISECONDS)  { cont.resume(true) }
    } else false

    fun addChannelForCourse(course: Course) {
        LOG.info("Adding a channel for the course ${course.course}")
        guild.controller.createTextChannel(course.shorthand ?: course.course.filter { it.isUpperCase() || it.isDigit()})
                .addPermissionOverride(guild.roles.find { it.name == "@everyone" }, mutableListOf<Permission>(), permissions)
                //.addPermissionOverride(guild.roles.find { it.name == "Bot" }, permissions, mutableListOf<Permission>())
                .setTopic(arrayOf(course.course, course.module, course.subject).filterNotNull().joinToString(" - "))
                .setParent(getCategory(course.subject))
                .queue { channel ->
                    guild.getTextChannelById(channel.idLong).sendMessage("Welcome on the newly created channel for ${course.course}!").queue()
                    databaseHelper.addChannelToCourse(course.courseId, channel.idLong)
                }
    }

    fun getCategory(subject: String): Category {
        if (guild.getCategoriesByName(subject, true).isEmpty()) {
            guild.controller.createCategory(subject).complete()
        }
        return guild.getCategoriesByName(subject, true).first()
    }

    fun addChannels() {
        val courses = databaseHelper.getCourses()
        courses.filter { it.userCount > 1 }.filter { databaseHelper.getChannelsForCourse(it.courseId).isEmpty() }.forEach {
            addChannelForCourse(it)
        }
    }
}