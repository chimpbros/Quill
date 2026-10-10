package com.yugentech.quill.category.repository

import com.yugentech.quill.database.dao.CategoryDao
import com.yugentech.quill.database.entity.CategoryEntity
import com.yugentech.theme.tokens.AppConstants.SHELF
import kotlinx.coroutines.flow.Flow

class CategoryRepositoryImpl(
    private val categoryDao: CategoryDao
) : CategoryRepository {

    override fun getAllCategories(): Flow<List<CategoryEntity>> =
        categoryDao.getAllCategories()

    override fun getUserCategories(): Flow<List<CategoryEntity>> =
        categoryDao.getUserCategories()

    override suspend fun getCategoryCount(): Int =
        categoryDao.getCategoryCount()

    override suspend fun initializeDefaultCategories() {
        val systemShelf = CategoryEntity(
            name = SHELF,
            sortOrder = 99,
            isSystem = true,
            isSynced = true
        )
        categoryDao.insertCategory(systemShelf)
    }

    override suspend fun insertCategory(name: String) {
        val entity = CategoryEntity(
            name = name,
            sortOrder = categoryDao.getCategoryCount(),
            isSystem = false,
            isSynced = false
        )
        categoryDao.insertCategory(entity)
    }

    override suspend fun updateCategory(category: CategoryEntity) {
        categoryDao.updateCategory(category.copy(isSynced = false))
    }

    override suspend fun updateCategories(categories: List<CategoryEntity>) {
        val unsyncedList = categories.map { it.copy(isSynced = false) }
        categoryDao.updateCategories(unsyncedList)
    }


    override suspend fun deleteCategory(category: CategoryEntity) {
        categoryDao.deleteCategory(category.name)
    }
}
