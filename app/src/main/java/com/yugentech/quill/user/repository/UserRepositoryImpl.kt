package com.yugentech.quill.user.repository
import com.yugentech.quill.database.dao.UserDao
import com.yugentech.quill.database.entity.UserEntity
import com.yugentech.quill.database.model.UserData
import kotlinx.coroutines.flow.map
class UserRepositoryImpl(private val userDao: UserDao, private val context: android.content.Context): UserRepository {
 override suspend fun getUser(userId:String) = userDao.getUser(userId)?.toUserData()
 override fun getUserFlow(userId:String) = userDao.getUserFlow(userId).map { it?.toUserData() ?: UserData(userId="local",name="Reader") }
 override suspend fun upsertUser(userData:UserData) { userDao.saveUser(UserEntity.fromUserData(userData.copy(userId="local")));context.getSharedPreferences("local_profile",0).edit().putString("name",userData.name).apply() }
}
