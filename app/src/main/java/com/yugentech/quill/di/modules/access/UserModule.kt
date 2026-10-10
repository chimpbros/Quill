package com.yugentech.quill.di.modules.access
import com.yugentech.quill.user.repository.*
import com.yugentech.quill.user.viewmodel.UserViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.core.qualifier.named
import com.yugentech.quill.user.datastore.UserDataStore
import org.koin.android.ext.koin.androidContext
val userModule = module {
 single { UserDataStore(get(named("user"))) }
 single<UserRepository> { UserRepositoryImpl(get(), androidContext()) }
 viewModel { UserViewModel(get(), get()) }
}
