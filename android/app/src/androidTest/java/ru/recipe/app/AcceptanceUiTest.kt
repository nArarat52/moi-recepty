package ru.recipe.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class AcceptanceUiTest {
    private val compose=createAndroidComposeRule<MainActivity>()
    private val cleanSession=object:ExternalResource() {
        override fun before() {
            val editor=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("recipe_session",0).edit().clear()
            InstrumentationRegistry.getArguments().getString("base_url")?.let { editor.putString("base_url",it) }
            editor.commit()
        }
    }
    @get:Rule val rules:RuleChain=RuleChain.outerRule(cleanSession).around(compose)
    private fun waitText(text:String,substring:Boolean=false) {
        try {
            compose.waitUntil(20000) { compose.onAllNodesWithText(text,substring=substring).fetchSemanticsNodes().isNotEmpty() }
        } catch(e:Throwable) {
            throw AssertionError("Ожидался текст: $text\n"+compose.onRoot().printToString(),e)
        }
        compose.waitForIdle()
    }
    private fun click(text:String) { compose.onNodeWithText(text).performClick() }
    private fun scroll(text:String) { compose.onNode(hasScrollAction()).performScrollToNode(hasText(text));compose.waitForIdle() }
    private fun login(login:String) {
        waitText("Чечевичный суп")
        compose.onNodeWithText("Профиль").performClick()
        click("Войти")
        compose.onNodeWithText("Логин").performTextInput(login)
        compose.onNodeWithText("Пароль").performTextInput("Recipe2026!")
        click("Войти")
        waitText("Чечевичный суп")
    }

    @Test fun reads_recover_after_idle_connection() = kotlinx.coroutines.runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val api=Api(SessionStore(context))
        repeat(3) {
            org.junit.Assert.assertEquals("ok",org.json.JSONObject(api.call("/health")).getString("status"))
            // Uvicorn closes idle keep-alive connections after five seconds.
            kotlinx.coroutines.delay(6500)
            org.junit.Assert.assertEquals(1,org.json.JSONObject(api.call("/recipes/1")).getInt("id"))
        }
    }

    @Test fun guest_catalog_search_detail() {
        waitText("Чечевичный суп")
        compose.onNodeWithText("Поиск по названию").performTextInput("ЧЕЧЕВ")
        compose.waitUntil(10000) { compose.onAllNodesWithText("Овощной салат").fetchSemanticsNodes().isEmpty() }
        click("Чечевичный суп")
        waitText("Ингредиенты")
        compose.onNodeWithText("Чечевица — 200 г").assertIsDisplayed()
        compose.onNodeWithText("Приготовление").assertExists()
    }
    @Test fun login_and_server_favorite() {
        login("anna")
        click("Чечевичный суп")
        waitText("Ингредиенты")
        compose.onNodeWithContentDescription("Сохранить в избранное").performClick()
        compose.onNodeWithText("Избранное").performClick()
        waitText("Чечевичный суп")
        click("Чечевичный суп")
        waitText("Ингредиенты")
        compose.onNodeWithContentDescription("Сохранить в избранное").performClick()
        compose.onNodeWithText("Избранное").performClick()
        waitText("В избранном пока нет рецептов")
    }
    @Test fun private_history_copy_and_cancel() {
        login("anna")
        compose.onNodeWithText("Группы").performClick()
        waitText("Семейная кухня")
        click("Семейная кухня")
        waitText("Идеальный чизкейк")
        click("Идеальный чизкейк")
        waitText("Попытка №1")
        click("Попытка №1")
        waitText("Результат приготовления")
        scroll("Создать вариант на основе этой")
        click("Создать вариант на основе этой")
        waitText("Название")
        compose.onNodeWithText("Название").assertTextContains("Идеальный чизкейк")
        compose.onNodeWithText("Название").performTextReplacement("Проверочный вариант")
        click("Отмена")
        waitText("Отменить заполнение?")
        compose.onNode(hasText("Отмена") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Название").assertTextContains("Проверочный вариант")
        click("Отмена")
        click("Выйти без сохранения")
        waitText("Результат приготовления")
    }
    @Test fun form_validation_keeps_input() {
        login("anna")
        click("+ Опубликовать рецепт")
        waitText("Название")
        compose.onNodeWithText("Название").performTextInput("Новый суп")
        scroll("Опубликовать")
        click("Опубликовать")
        waitText("Проверьте поля формы:",true)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Название"))
        compose.onNodeWithText("Название").assertTextContains("Новый суп")
        click("Отмена")
        waitText("Отменить заполнение?")
        click("Выйти без сохранения")
        waitText("Чечевичный суп")
    }
    @Test fun expired_group_is_read_only() {
        login("mikhail")
        compose.onNodeWithText("Группы").performClick()
        waitText("Тестовая лаборатория")
        click("Тестовая лаборатория")
        waitText("Доступен только просмотр")
        compose.onNodeWithText("+ Новая разработка блюда").assertIsNotEnabled()
        click("Идеальный чизкейк")
        waitText("+ Новая попытка")
        compose.onNodeWithText("+ Новая попытка").assertIsNotEnabled()
    }
    @Test fun review_create_edit_delete() {
        login("olga")
        click("Чечевичный суп")
        waitText("Ингредиенты")
        scroll("Оценки и отзывы")
        click("Оценки и отзывы")
        waitText("Ваш отзыв")
        compose.onNodeWithText("Ваш отзыв").performTextInput("Проверка отзыва из Android")
        click("Сохранить отзыв")
        waitText("Удалить свой отзыв")
        click("Удалить свой отзыв")
        waitText("Удалить отзыв?")
        click("Удалить")
        waitText("Ваш отзыв")
    }
}
