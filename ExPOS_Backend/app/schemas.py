from pydantic import BaseModel

class ProductCreate(BaseModel):
    name: str
    price: float

class ProductResponse(ProductCreate):
    id: int

    model_config = {
        "from_attributes": True
    }