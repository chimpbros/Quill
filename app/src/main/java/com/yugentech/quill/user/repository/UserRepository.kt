package com.yugentech.quill.user.repository
import com.yugentech.quill.database.model.UserData
import kotlinx.coroutines.flow.Flow
interface UserRepository {
 suspend fun getUser(userId: String): UserData?
 fun getUserFlow(userId: String): Flow<UserData?>
 suspend fun upsertUser(userData: UserData)
}
