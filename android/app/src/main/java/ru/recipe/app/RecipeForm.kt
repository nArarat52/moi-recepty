package ru.recipe.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

private fun preview(bytes:ByteArray):Bitmap? {
    val info=BitmapFactory.Options().apply { inJustDecodeBounds=true }
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,info)
    var sample=1
    while(info.outWidth/sample>1200||info.outHeight/sample>1200)sample*=2
    return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })
}
@Composable fun RemotePhoto(vm:AppState,id:String) {
    var attempt by remember(id) { mutableIntStateOf(0) }
    var failed by remember(id) { mutableStateOf(false) }
    val image by produceState<Bitmap?>(null,id,vm.user?.optLong("id"),attempt) {
        value=null;failed=false
        try { val bytes=vm.api.image(id);value=withContext(Dispatchers.Default){preview(bytes)};failed=value==null }
        catch(e:Exception){failed=true}
    }
    if(image!=null)Image(image!!.asImageBitmap(),"Фотография блюда",Modifier.fillMaxWidth().heightIn(min=120.dp,max=240.dp),contentScale=ContentScale.Crop)
    else if(failed)TextButton(onClick={attempt++}){Text("Фото недоступно · Повторить")}
    else LinearProgressIndicator(Modifier.fillMaxWidth())
}
@Composable fun RecipeFormScreen(vm:AppState) {
    val d=vm.draft ?: return
    val context=LocalContext.current
    var localErrors by remember(d) { mutableStateOf<Map<String,String>>(emptyMap()) }
    val errors=localErrors+(vm.error?.fields ?: emptyMap())
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri->
        if(uri!=null)vm.task {
            val result=withContext(Dispatchers.IO) {
                val mime=context.contentResolver.getType(uri)
                if(mime !in listOf("image/jpeg","image/png"))throw ApiFailure(422,"image_format","Выберите JPEG или PNG")
                val bytes=context.contentResolver.openInputStream(uri)?.use { input->
                    val out=ByteArrayOutputStream()
                    val buffer=ByteArray(8192)
                    while(true) {
                        val count=input.read(buffer)
                        if(count<0)break
                        out.write(buffer,0,count)
                        if(out.size()>5242880)throw ApiFailure(422,"image_size","Размер фото превышает 5 МиБ")
                    }
                    out.toByteArray()
                } ?: throw ApiFailure(422,"image_read","Не удалось прочитать фото")
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth.toLong()*bounds.outHeight>25000000)throw ApiFailure(422,"image_format","Изображение повреждено или слишком большое")
                bytes to mime!!
            }
            d.photoBytes=result.first;d.photoMime=result.second
        }
    }
    ContentList {
        if(d.parentId>0)item { Text("Новая версия на основе предыдущей попытки. Исходная запись сохранится.",fontSize=13.sp,color=Muted) }
        item { Field(d.title,"Название",{d.title=it},errors["title"],!vm.busy) }
        item { Field(d.description,"Описание · необязательно",{d.description=it},errors["description"],!vm.busy,multiline=true) }
        item { Choice("Категория",d.category,Categories,!vm.busy){d.category=it} }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)){Field(d.minutes,"Время, мин",{d.minutes=it},errors["minutes"],!vm.busy,numeric=true)}
            Column(Modifier.weight(1f)){Field(d.servings,"Порции",{d.servings=it},errors["servings"],!vm.busy,numeric=true)}
        } }
        item { Text("Ингредиенты",fontSize=20.sp) }
        itemsIndexed(d.ingredients) { i,v->
            InfoCard("Ингредиент "+(i+1)) {
                Field(v.name,"Название ингредиента",{v.name=it},errors["ingredients.$i.name"],!vm.busy)
                Field(v.amount,"Количество",{v.amount=it},errors["ingredients.$i.amount"],!vm.busy,numeric=true)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Choice("Ед.",v.unit,Units,!vm.busy){v.unit=it}
                    IconButton(onClick={d.ingredients.removeAt(i)},enabled=!vm.busy&&d.ingredients.size>1){Icon(Icons.Default.Delete,"Удалить ингредиент "+(i+1))}
                }
            }
        }
        item { OutlinedButton(onClick={d.ingredients.add(IngredientDraft())},enabled=!vm.busy&&d.ingredients.size<50){Text("+ Ингредиент")} }
        item { Text("Приготовление",fontSize=20.sp) }
        itemsIndexed(d.steps) { i,s->
            Column {
                Field(s,"Шаг "+(i+1),{d.steps[i]=it},errors["steps.$i"],!vm.busy,multiline=true)
                Row {
                    IconButton(onClick={val old=d.steps[i-1];d.steps[i-1]=d.steps[i];d.steps[i]=old},enabled=!vm.busy&&i>0){Icon(Icons.Default.ArrowUpward,"Переместить шаг вверх")}
                    IconButton(onClick={val old=d.steps[i+1];d.steps[i+1]=d.steps[i];d.steps[i]=old},enabled=!vm.busy&&i<d.steps.lastIndex){Icon(Icons.Default.ArrowDownward,"Переместить шаг вниз")}
                    IconButton(onClick={d.steps.removeAt(i)},enabled=!vm.busy&&d.steps.size>1){Icon(Icons.Default.Delete,"Удалить шаг "+(i+1))}
                }
            }
        }
        item { OutlinedButton(onClick={d.steps.add("")},enabled=!vm.busy&&d.steps.size<30){Text("+ Шаг")} }
        item {
            val bytes=d.photoBytes
            if(bytes!=null) {
                val bitmap=remember(bytes){preview(bytes)}
                bitmap?.let { Image(it.asImageBitmap(),"Выбранное фото",Modifier.fillMaxWidth().height(180.dp),contentScale=ContentScale.Crop) }
            } else if(d.imageId.isNotEmpty())RemotePhoto(vm,d.imageId)
            Row {
                OutlinedButton(onClick={picker.launch("image/*")},enabled=!vm.busy){Text(if(bytes!=null||d.imageId.isNotEmpty())"Заменить фото" else "+ Фото")}
                if(bytes!=null||d.imageId.isNotEmpty())TextButton(onClick={d.photoBytes=null;d.imageId=""},enabled=!vm.busy){Text("Убрать")}
            }
            Text("JPEG или PNG · до 5 МиБ · необязательно",fontSize=12.sp,color=Muted)
        }
        if(d.dishId>0)item { Field(d.note,"Результат приготовления",{d.note=it},errors["note"],!vm.busy,multiline=true) }
        if(localErrors.isNotEmpty())item { Text("Проверьте поля формы: "+localErrors.values.distinct().joinToString(". "),fontSize=13.sp,color=MaterialTheme.colorScheme.error) }
        item { PrimaryButton(if(d.dishId>0)"Сохранить новую версию" else if(d.recipeId>0)"Сохранить изменения" else "Опубликовать",!vm.busy) {
            localErrors=d.validate()
            if(localErrors.isEmpty())vm.task {
                d.photoBytes?.let { bytes->
                    d.imageId=vm.api.upload(bytes,d.photoMime,if(d.groupId>0)d.groupId else null)
                    d.photoBytes=null
                }
                val body=d.payload()
                val path=if(d.dishId>0)"/dishes/"+d.dishId+"/attempts" else if(d.recipeId>0)"/recipes/"+d.recipeId else "/recipes"
                val result=JSONObject(vm.api.call(path,if(d.recipeId>0)"PUT" else "POST",body,if(d.recipeId>0)null else d.operationKey(body)))
                vm.notice=if(d.dishId>0)"Новая версия сохранена" else "Рецепт сохранён"
                vm.draft=null
                vm.completeBack()
                if(d.dishId==0L&&vm.route.kind!="recipe")vm.complete(Route("recipe",result.getLong("id")))
            }
        } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
